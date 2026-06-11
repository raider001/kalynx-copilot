package copilot.agent;

import com.google.gson.*;
import com.intellij.openapi.project.Project;
import copilot.CopilotSettings;
import copilot.CopilotUtil;
import copilot.context.ContextManager;
import copilot.agent.Phase;
import copilot.agent.PhaseController;
import copilot.chat.ChatMessage;
import copilot.chat.ConversationHistory;
import copilot.tools.api.AgentTool;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.LinkedHashSet;

/**
 * Drives the agentic loop: sends messages to the LLM, handles tool calls,
 * and returns the final natural-language response.
 *
 * <p>Usage (must be called on a background thread):
 * <pre>
 *   AgentSession session = new AgentSession(project);
 *   String response = session.chat("Explain this method", statusConsumer);
 * </pre>
 */
public class AgentSession {

    private static final int DEFAULT_MAX_ITERATIONS = 100;
    /** Tool results larger than this are truncated before being stored in conversation history. */
    private static final int MAX_HISTORY_RESULT_CHARS = 50_000;

    /**
     * Matches a Python or JSON code fence whose body is a single JSON object.
     * Used by {@link #extractTextToolCalls} when the model outputs tool calls as text
     * rather than via the OpenAI tool_calls field.
     */
    private static final java.util.regex.Pattern TEXT_TOOL_FENCE =
            java.util.regex.Pattern.compile(
                    "```(?:python|json)?\\s*\\r?\\n([\\s\\S]*?)(?:\\r?\\n)?```",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Tools that mutate state — calling one resets the duplicate-call detector. */
    private static final Set<String> WRITE_TOOLS =
            Set.of("replace_in_file", "create_file", "create_plan", "update_plan", "complete_phase");
    private final Project project;
    private       String  baseSysPmt;
    private       String  modePrompt = "";

    // Editable system-prompt sections — per session, initialised from static defaults.
    private String sectionDynamicContext;
    private String sectionAgenticWorkflow;
    private String sectionGuidelines;
    private final ConversationHistory history = new ConversationHistory();
    // Initialised lazily on the first chat() call — the extension point is not guaranteed
    // to be registered yet when the constructor runs (tool window init happens early).
    private List<AgentTool>        tools   = null;
    private final Map<String, AgentTool> toolMap = new HashMap<>();

    /** Set to {@code true} to abort the agentic loop after the current HTTP call finishes. */
    private volatile boolean cancelled = false;

    /** Current compression trigger threshold (tokens). Adjusted dynamically after each compression. */
    private int currentCompressionThreshold = 0;
    private boolean compressionInitialized  = false;

    /** Budget tracking for the manifest header (#5). */
    private int currentIterations = 0;
    private int currentTokens     = 0;

    /** Stable-prefix cache for prompt caching (#6). Invalidated on phase change or section edit. */
    private String cachedStablePrefix = null;
    private Phase  cachedPhase        = null;
    private String cachedModePrompt   = null;

    /**
     * Cancels the current request. Disconnects the in-flight HTTP connection so the
     * background thread unblocks as quickly as possible.
     */
    public void cancel() {
        cancelled = true;
        CopilotUtil.cancelCurrentRequest();
    }

    /** Returns the effective system prompt (custom if set, otherwise the built-in default). */
    public String getEffectiveSystemPrompt() { return baseSysPmt; }

    /** Updates the mode prompt prefix — applied from the next {@link #chat} call onward. */
    public void setModePrompt(String prompt) {
        this.modePrompt = prompt == null ? "" : prompt;
        cachedPhase = null; // invalidate stable prefix cache
    }

    public AgentSession(Project project) {
        this.project = project;
        initSections(null, null, null);
    }

    /**
     * Creates a session pre-populated with user-edited section text.
     * Pass {@code null} for any section to use the static default.
     */
    public AgentSession(Project project,
                        String dynamicContext,
                        String agenticWorkflow,
                        String guidelines) {
        this.project = project;
        initSections(dynamicContext, agenticWorkflow, guidelines);
    }

    private void initSections(String dynamicContext, String agenticWorkflow, String guidelines) {
        // Custom agent-level system prompt overrides the section system entirely.
        String custom = CopilotSettings.getInstance().getSystemPrompt();
        if (custom != null && !custom.isBlank()) {
            baseSysPmt = custom;
            sectionDynamicContext  = null;
            sectionAgenticWorkflow = null;
            sectionGuidelines      = null;
        } else {
            sectionDynamicContext  = dynamicContext  != null ? dynamicContext  : defaultDynamicContextSection();
            sectionAgenticWorkflow = agenticWorkflow != null ? agenticWorkflow : defaultAgenticWorkflowSection();
            sectionGuidelines      = guidelines      != null ? guidelines      : defaultGuidelinesSection();
            baseSysPmt = assembleSystemPrompt();
        }
        cachedPhase = null; // invalidate stable prefix cache
        history.setSystemMessage(baseSysPmt);
    }

    /**
     * Re-applies the system-prompt sections (e.g. after a settings change) without
     * touching the conversation history. Use this instead of recreating the session when
     * only the prompt or agent configuration has changed — preserves retained tool history.
     */
    public void reinitSections(String dynamicContext, String agenticWorkflow, String guidelines) {
        initSections(dynamicContext, agenticWorkflow, guidelines);
    }

    // --- Section setters (called by the UI when the user edits a section tab) ---

    public void setDynamicContextSection(String text) {
        if (sectionDynamicContext == null) return; // custom prompt active — ignore
        sectionDynamicContext = text;
        cachedPhase = null;
        baseSysPmt = assembleSystemPrompt();
    }

    public void setAgenticWorkflowSection(String text) {
        if (sectionAgenticWorkflow == null) return;
        sectionAgenticWorkflow = text;
        cachedPhase = null;
        baseSysPmt = assembleSystemPrompt();
    }

    public void setGuidelinesSection(String text) {
        if (sectionGuidelines == null) return;
        sectionGuidelines = text;
        cachedPhase = null;
        baseSysPmt = assembleSystemPrompt();
    }

    private String assembleSystemPrompt() {
        return staticPromptSection()
                + "\n\n" + sectionDynamicContext
                + "\n\n" + sectionAgenticWorkflow
                + "\n\n" + sectionGuidelines;
    }

    public String chat(String userMessage, AgentCallback callback) {
        cancelled = false; // reset for reuse

        // Discover tools on first use — EP is guaranteed to be registered by the time
        // the user sends their first message, even if not at constructor time.
        if (tools == null) {
            try {
                tools = new ArrayList<>(AgentTool.EP_NAME.getExtensionList());
            } catch (IllegalArgumentException e) {
                // EP not registered — plugin.xml version mismatch or running outside plugin context.
                // Fall back to the built-in file tools so the agent remains functional.
                tools = new ArrayList<>(java.util.Arrays.asList(
                        new copilot.agent.tools.GetCurrentFileTool(),
                        new copilot.agent.tools.ListFilesTool(),
                        new copilot.agent.tools.AddToContextTool(),
                        new copilot.agent.tools.RemoveFromContextTool(),
                        new copilot.agent.tools.ListContextTool(),
                        new copilot.agent.tools.ClearContextTool(),
                        new copilot.agent.tools.ReplaceInFileTool(),
                        new copilot.agent.tools.CreateFileTool(),
                        new copilot.agent.tools.CreatePlanTool(),
                        new copilot.agent.tools.UpdatePlanTool(),
                        new copilot.agent.tools.SearchInFilesTool(),
                        new copilot.agent.tools.ReadFileTool(),
                        new copilot.agent.tools.CompileProjectTool(),
                        new copilot.agent.tools.GetIDEProblemsTool(),
                        new copilot.agent.tools.ScanProblemsTool(),
                        new copilot.agent.tools.RunTestsTool(),
                        new copilot.agent.tools.FinishTaskTool(),
                        new copilot.agent.tools.CompletePhaseTool(),
                        new copilot.agent.tools.RememberTool(),
                        new copilot.agent.tools.AskUserTool(),
                        new copilot.agent.tools.UpdateScratchpadTool()
                ));
            }
            for (AgentTool t : tools) toolMap.put(t.getName(), t);
        }

        // Refresh system message so mode, context-file changes, etc. are picked up each turn
        ContextManager cm = ContextManager.getInstance(project);
        PhaseController pc = cm.getPhaseController();

        // Auto-reset to ANALYSE when the previous task completed
        if (pc.getPhase() == Phase.DONE) pc.resetForNewTask();

        history.setSystemMessage(buildFullSystemMessage(cm, pc));

        history.add(ChatMessage.user(userMessage));

        // Fetch the model's context window in a separate thread so it runs in parallel
        // with the first LLM request. getNow(0) is non-blocking: returns 0 if not yet
        // resolved, and the result is cached so all subsequent calls are instant.
        CopilotSettings settings = CopilotSettings.getInstance();
        int maxIterations = settings.getActiveAgent().maxIterations;
        if (maxIterations <= 0) maxIterations = DEFAULT_MAX_ITERATIONS;

        // Resolve context window before entering the loop — we're on a background thread so
        // blocking is safe and guarantees num_ctx / max_tokens are correct from the first request.
        int resolvedCtxWindow = 0;
        try {
            resolvedCtxWindow = CompletableFuture.supplyAsync(() ->
                    CopilotUtil.fetchContextWindow(
                            settings.getApiEndpoint(), settings.getApiKey(), settings.getModel()))
                    .get(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {} // proceed with 0 (unknown) if fetch times out or fails

        int lastPromptTokens = 0; // updated after each response; used to cap max_tokens

        initCompression();

        // Tracks read-only tool signatures (name + args) called since the last write.
        // Cleared whenever a write tool (replace_in_file, create_file, etc.) is called.
        // A repeat signature means the model is looping without making progress.
        Set<String> callsSinceLastWrite = new LinkedHashSet<>();

        // Tracks the signature of the last write tool call to detect consecutive identical writes.
        String lastWriteSig = null;

        int iterations = 0;
        while (iterations < maxIterations) {
            if (cancelled) return "⏹ Request cancelled.";
            iterations++;
            currentIterations = iterations;

            // Cap max_tokens so prompt + output never exceeds the context window.
            // Use the agent's configured maxOutputTokens as the ceiling; shrink only
            // when the remaining context window is smaller than that.
            int configuredMax = CopilotSettings.getInstance().getActiveAgent().maxOutputTokens;
            if (configuredMax <= 0) configuredMax = 16384;
            int maxTokens = (resolvedCtxWindow > 0 && lastPromptTokens > 0)
                    ? Math.min(configuredMax, Math.max(256, resolvedCtxWindow - lastPromptTokens - 512))
                    : configuredMax;

            // Buffers filled by the streaming callbacks
            final StringBuilder streamedContent  = new StringBuilder();
            final boolean[]     hadReasoning     = {false};

            // Silence timeout: if no token arrives for 60 s, cancel and surface a clear error.
            final long[]    lastTokenTime = {System.currentTimeMillis()};
            final boolean[] timedOut      = {false};

            JsonObject response;
            try {
                response = CopilotUtil.streamChatRequest(
                        history.getMessages(), tools,
                        maxTokens,
                        resolvedCtxWindow,
                        chunk -> {
                            lastTokenTime[0] = System.currentTimeMillis();
                            streamedContent.append(chunk);
                            callback.onResponseChunk(chunk);
                        },
                        chunk -> {
                            lastTokenTime[0] = System.currentTimeMillis();
                            hadReasoning[0] = true;
                            callback.onThinkingChunk(chunk);
                        },
                        () -> {
                            if (cancelled) return true;
                            if (System.currentTimeMillis() - lastTokenTime[0] > 60_000L) {
                                timedOut[0] = true;
                                CopilotUtil.cancelCurrentRequest();
                                return true;
                            }
                            return false;
                        });
            } catch (Exception e) {
                if (timedOut[0])
                    return "⚠️ No response received for 60 seconds — the model may be stuck or the connection dropped.";
                if (cancelled) return "⏹ Request cancelled.";
                return "Error communicating with the API: " + e.getMessage();
            }

            // Reasoning was streamed live — signal end so the UI can finalise the bubble.
            if (hadReasoning[0]) callback.onThinkingStreamEnd();

            // Emit token usage so the UI can update the context bar.
            // ctxFuture.getNow(0) is non-blocking — returns 0 until the fetch resolves
            // (typically before the first response arrives), then the cached value forever.
            if (response.has("usage") && !response.get("usage").isJsonNull()) {
                JsonObject usage = response.getAsJsonObject("usage");
                int promptTokens     = usage.has("prompt_tokens")     ? usage.get("prompt_tokens").getAsInt()     : 0;
                int completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").getAsInt() : 0;
                lastPromptTokens = promptTokens; // track for next iteration's max_tokens calculation
                currentTokens    = promptTokens;
                callback.onUsage(promptTokens, completionTokens, resolvedCtxWindow);
            }

            JsonObject choice  = response.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");

            // Detect token budget exhaustion — the model was cut off before finishing.
            String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
                    ? choice.get("finish_reason").getAsString() : null;
            if ("length".equals(finishReason) && !message.has("tool_calls")) {
                int limit = CopilotSettings.getInstance().getActiveAgent().maxOutputTokens;
                return "⚠️ Response cut off: the model reached its Max Output Tokens limit ("
                        + limit + ") before producing a response. "
                        + "Increase 'Max Output Tokens' in agent settings — "
                        + "reasoning models often need 32,768 or more.";
            }

            // Text-based tool-call fallback: some local models (e.g. Llama 3.3 via LM Studio)
            // embed tool calls as JSON inside Python/JSON code fences instead of using the
            // OpenAI tool_calls field. Parse and inject them only when explicitly enabled,
            // and only when the name matches a registered tool (safety gate).
            boolean wasTextToolCall = false;
            if (!message.has("tool_calls")
                    && CopilotSettings.getInstance().getActiveAgent().parseTextToolCalls) {
                JsonArray synthetic = extractTextToolCalls(streamedContent.toString());
                if (synthetic != null) {
                    message.add("tool_calls", synthetic);
                    wasTextToolCall = true;
                }
            }

            // --- Model wants to call tools ---
            if (message.has("tool_calls")) {
                // The streamed content was narration (e.g. "I'll look at the files…"), not the
                // final response. Tell the UI to reclassify it as a thinking block.
                callback.onResponseAborted();
                String narration = streamedContent.toString().trim();
                if (!narration.isEmpty()) callback.onThinking(narration);

                JsonArray toolCalls = message.getAsJsonArray("tool_calls");
                // For text-based tool calls, preserve the original content in history so the
                // model sees a consistent picture of what it output on the prior turn.
                if (wasTextToolCall && !narration.isEmpty()) {
                    history.add(ChatMessage.assistantWithToolCalls(toolCalls, narration));
                } else {
                    history.add(ChatMessage.assistantWithToolCalls(toolCalls));
                }

                for (JsonElement tcElem : toolCalls) {
                    JsonObject tc       = tcElem.getAsJsonObject();
                    String callId       = tc.get("id").getAsString();
                    String toolName     = tc.getAsJsonObject("function").get("name").getAsString();
                    String argsStr      = tc.getAsJsonObject("function").get("arguments").getAsString();
                    JsonObject args     = JsonParser.parseString(argsStr).getAsJsonObject();

                    AgentTool tool = toolMap.get(toolName);
                    String description = tool != null ? tool.getStatusMessage(args) : toolName;
                    callback.onToolStart(toolName, description);

                    // --- Stuck-loop detection ---
                    // Write tools reset the read-tracker; consecutive identical writes are caught via lastWriteSig.
                    String toolResult;
                    boolean success = true;
                    boolean isDuplicate = false;

                    String sig = toolName + " " + argsStr;
                    if (WRITE_TOOLS.contains(toolName)) {
                        if (sig.equals(lastWriteSig)) {
                            isDuplicate = true; // same write called twice in a row
                        } else {
                            lastWriteSig = sig;
                            callsSinceLastWrite.clear();
                        }
                    } else {
                        if (callsSinceLastWrite.contains(sig)) {
                            isDuplicate = true;
                        } else {
                            callsSinceLastWrite.add(sig);
                        }
                    }

                    if (isDuplicate) {
                        boolean isWrite = WRITE_TOOLS.contains(toolName);
                        toolResult = "LOOP DETECTED: '" + toolName + "' was already called with these " +
                                     "exact arguments " + (isWrite ? "(your edit was already applied)" : "since your last code change") + " " +
                                     " — the result will be identical.\n" +
                                     (isWrite
                                       ? "The compile error is on DIFFERENT lines.\n" +
                                         "Required next action:\n" +
                                         "  " + (char)0x2022 + " Call read_file to get the current file content.\n" +
                                         "  " + (char)0x2022 + " Identify which lines still cause the compile error.\n" +
                                         "  " + (char)0x2022 + " Fix THOSE lines " + (char)0x2014 + " do not repeat this edit.\n" +
                                         "  " + (char)0x2022 + " If you cannot see a new approach, call ask_user."
                                       : "You must make a code change before calling this tool again.\n" +
                                         "Required next action:\n" +
                                         "  " + (char)0x2022 + " Identify the specific error from the output you already received.\n" +
                                         "  " + (char)0x2022 + " Use replace_in_file to fix it, then re-run.\n" +
                                         "  " + (char)0x2022 + " If you cannot determine how to fix it, call ask_user.");
                        success = false;
                    } else {
                        try {
                            if (tool == null) {
                                toolResult = "Error: unknown tool \"" + toolName + "\"";
                                success = false;
                            } else {
                                toolResult = tool.execute(args, project);

                                // --- Finish signal ---
                                if (toolResult != null && toolResult.startsWith(
                                        copilot.agent.tools.FinishTaskTool.FINISH_SIGNAL)) {
                                    String summary = toolResult.substring(
                                            copilot.agent.tools.FinishTaskTool.FINISH_SIGNAL.length()).trim();
                                    history.add(ChatMessage.toolResult(callId, toolName, "Task marked complete."));
                                    callback.onToolEnd(toolName, true);
                                    history.add(ChatMessage.assistant(summary));
                                    if (!CopilotSettings.getInstance().retainToolHistory)
                                        history.pruneToolMessages();
                                    maybeCompress(lastPromptTokens, callback);
                                    return summary;
                                }

                                // --- Ask user signal ---
                                if (toolResult != null && toolResult.startsWith(
                                        copilot.agent.tools.AskUserTool.ASK_SIGNAL)) {
                                    String question = toolResult.substring(
                                            copilot.agent.tools.AskUserTool.ASK_SIGNAL.length()).trim();
                                    history.add(ChatMessage.toolResult(callId, toolName, "Question posed to user."));
                                    callback.onToolEnd(toolName, true);
                                    maybeCompress(lastPromptTokens, callback);
                                    return question;
                                }

                                if (toolResult != null && toolResult.startsWith("Error:")) {
                                    success = false;
                                }
                            }
                        } catch (Exception e) {
                            toolResult = "Error executing " + toolName + ": " + e.getMessage();
                            success = false;
                        }
                    }

                    // Truncate large results before storing in history to prevent context overflow.
                    // The full result is still shown in the UI via onToolResult below.
                    String storedResult = toolResult != null && toolResult.length() > MAX_HISTORY_RESULT_CHARS
                            ? toolResult.substring(0, MAX_HISTORY_RESULT_CHARS)
                              + "\n... [truncated — " + toolResult.length() + " total chars]"
                            : toolResult;
                    history.add(ChatMessage.toolResult(callId, toolName, storedResult));
                    callback.onToolEnd(toolName, success);
                    if (!success) callback.onToolError(toolName, toolResult);
                    if ("replace_in_file".equals(toolName) && success
                            && args.has("old_code") && args.has("new_code")) {
                        String fp = args.has("file_path") ? args.get("file_path").getAsString() : "";
                        callback.onEditResult(fp,
                                args.get("old_code").getAsString(),
                                args.get("new_code").getAsString());
                    } else if (tool != null && tool.shouldShowResultInChat()) {
                        callback.onToolResult(toolName, toolResult);
                    }
                }

                // Refresh system message so the model sees the latest pinned file content
                // (edits made this round are now reflected in the live file reads).
                history.setSystemMessage(buildFullSystemMessage(cm, pc));

                // continue loop to feed tool results back to the model

            } else {
                // --- Final text response (already streamed live) ---
                String content = streamedContent.toString().trim();

                if (!content.isEmpty()) {
                    history.add(ChatMessage.assistant(content));
                    if (!CopilotSettings.getInstance().retainToolHistory)
                        history.pruneToolMessages();
                    maybeCompress(lastPromptTokens, callback);
                    return content;
                }

                // Empty response with no tool calls — the model has nothing to say.
                // Do NOT loop: that would hammer the endpoint with identical requests.
                // This typically means the model doesn't support the tool-call format,
                // or tool_choice is misconfigured.
                return "⚠️ Model returned an empty response with no tool calls. "
                     + "It may not support the configured tool format. "
                     + "Try setting Tool Choice to 'none' in agent settings to test plain chat, "
                     + "or switch to a model with native function-calling support.";
            }
        }

        return "⚠️ Agent reached the maximum iteration limit without producing a final answer.";
    }

    // ── Compression helpers ───────────────────────────────────────────────────

    private void initCompression() {
        if (compressionInitialized) return;
        compressionInitialized = true;
        currentCompressionThreshold = CopilotSettings.getInstance().getActiveAgent().compressionThresholdTokens;
    }

    /**
     * Triggers conversation compression if {@code promptTokens} exceeds the current threshold.
     * Saves a pre-compression snapshot, prunes old tool pairs, calls the LLM to summarize
     * assistant turns, replaces history, then adjusts the threshold so the next compression
     * is triggered at roughly the same relative growth above the compressed baseline.
     */
    private void maybeCompress(int promptTokens, AgentCallback callback) {
        if (currentCompressionThreshold <= 0 || promptTokens < currentCompressionThreshold) return;

        callback.onCompressionStarted();

        String snapshotPath = saveSnapshot();
        extractFactsBeforeCompression(); // save durable knowledge before crushing turns
        history.pruneOldToolPairs(20);

        List<ChatMessage> compressed = callCompressionLlm();
        if (compressed != null && !compressed.isEmpty()) {
            history.replaceMessages(compressed);
        }

        int compressedEstimate = history.estimateContentChars() / 4;
        // Push the next trigger to ~80% above the new compressed size — big enough to be useful,
        // small enough to prevent runaway growth.
        int configured = CopilotSettings.getInstance().getActiveAgent().compressionThresholdTokens;
        currentCompressionThreshold = Math.max((int)(compressedEstimate * 1.8), configured);

        callback.onCompressionDone(promptTokens, compressedEstimate, snapshotPath);
    }

    /**
     * Saves the current conversation history to a Markdown file under
     * {@code <project_root>/.kalynx/snapshots/}.
     *
     * @return the absolute path to the file, or null if saving failed
     */
    private String saveSnapshot() {
        try {
            String basePath = project.getBasePath();
            if (basePath == null) return null;

            java.io.File dir = new java.io.File(basePath, ".kalynx/snapshots");
            dir.mkdirs();

            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            java.io.File file = new java.io.File(dir, "snapshot_" + ts + ".md");

            StringBuilder sb = new StringBuilder();
            sb.append("# Conversation Snapshot — ").append(ts).append("\n\n");
            for (ChatMessage msg : history.snapshot()) {
                if (msg.getRole() == ChatMessage.Role.SYSTEM) continue;
                sb.append("## ").append(msg.getRole().name().toLowerCase()).append("\n\n");
                String c = msg.getContent();
                if (c != null && !c.isBlank()) sb.append(c).append("\n\n");
            }

            try (java.io.FileWriter fw = new java.io.FileWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
                fw.write(sb.toString());
            }
            return file.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Asks the configured LLM to compress the conversation: USER messages are kept verbatim,
     * ASSISTANT messages are replaced with concise bullet-point summaries.
     *
     * @return the compressed message list, or null if the call failed or returned invalid JSON
     */
    private List<ChatMessage> callCompressionLlm() {
        try {
            StringBuilder conv = new StringBuilder();
            for (ChatMessage msg : history.snapshot()) {
                ChatMessage.Role role = msg.getRole();
                if (role == ChatMessage.Role.SYSTEM || role == ChatMessage.Role.TOOL) continue;
                String c = msg.getContent();
                if (c == null || c.isBlank()) continue;
                String label = msg.hasToolCalls() ? "assistant (tool call)" : role.name().toLowerCase();
                conv.append("[").append(label).append("]\n").append(c).append("\n\n");
            }
            if (conv.length() == 0) return null;

            String prompt =
                "You are a conversation compressor. Compress the conversation below.\n" +
                "Rules:\n" +
                "  - Preserve every USER message exactly as-is.\n" +
                "  - Replace each ASSISTANT message with 3-7 bullet points summarising facts, decisions, and actions. Resolve contradictions: newest wins.\n" +
                "  - Drop all tool-call assistant turns entirely — they are captured in adjacent summaries.\n" +
                "  - Return ONLY a JSON array: [{\"role\":\"user\"|\"assistant\",\"content\":\"...\"}]\n" +
                "  - No markdown wrapper, no explanation — raw JSON only.\n\n" +
                "Conversation:\n\n" + conv;

            CopilotSettings s = CopilotSettings.getInstance();
            String reqBody = buildCompressionRequest(s.getModel(), prompt);
            String raw = sendCompressionRequest(s.getApiEndpoint(), s.getApiKey(), reqBody);
            if (raw == null || raw.isBlank()) return null;

            String trimmed = raw.trim();
            if (trimmed.startsWith("```")) {
                trimmed = trimmed.replaceFirst("^```(?:json)?\\s*\\r?\\n?", "")
                                 .replaceFirst("\\r?\\n?```\\s*$", "").trim();
            }

            JsonArray arr = JsonParser.parseString(trimmed).getAsJsonArray();
            List<ChatMessage> result = new ArrayList<>();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String role = obj.get("role").getAsString();
                String content = obj.has("content") ? obj.get("content").getAsString() : "";
                if ("user".equals(role))      result.add(ChatMessage.user(content));
                else if ("assistant".equals(role)) result.add(ChatMessage.assistant(content));
            }
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildCompressionRequest(String model, String prompt) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", prompt);
        messages.add(msg);
        body.add("messages", messages);
        body.addProperty("max_tokens", 8192);
        body.addProperty("temperature", 0.1);
        body.addProperty("stream", false);
        return body.toString();
    }

    private static String sendCompressionRequest(String endpoint, String apiKey,
                                                  String body) throws java.io.IOException {
        java.net.URL url = new java.net.URL(endpoint);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        if (apiKey != null && !apiKey.trim().isEmpty())
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(120_000);
        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (conn.getResponseCode() != java.net.HttpURLConnection.HTTP_OK) return null;
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            JsonObject resp = JsonParser.parseString(sb.toString().trim()).getAsJsonObject();
            return resp.getAsJsonArray("choices").get(0)
                       .getAsJsonObject().getAsJsonObject("message")
                       .get("content").getAsString();
        }
    }

    /**
     * Scans {@code content} for JSON tool calls embedded in Python/JSON code fences.
     * Accepts both Meta format {"type":"function","name":"...","parameters":{}} and
     * the simpler {"name":"...","arguments":{}}.
     *
     * Safety gate: only returns calls whose "name" is a registered tool — prevents
     * accidental matches on legitimate code examples in the model's response.
     *
     * @return a JsonArray of synthetic OpenAI-format tool_call objects, or null if none found.
     */
    private JsonArray extractTextToolCalls(String content) {
        java.util.regex.Matcher m = TEXT_TOOL_FENCE.matcher(content);
        JsonArray calls = new JsonArray();
        int idx = 0;
        while (m.find()) {
            String body = m.group(1).trim();
            try {
                JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                if (!obj.has("name")) continue;
                String name = obj.get("name").getAsString();
                if (!toolMap.containsKey(name)) continue;  // safety gate

                JsonElement argsEl = obj.has("parameters") ? obj.get("parameters")
                                   : obj.has("arguments")  ? obj.get("arguments")
                                   : new JsonObject();
                String argsStr = argsEl.isJsonObject() ? argsEl.getAsJsonObject().toString() : "{}";

                JsonObject call = new JsonObject();
                call.addProperty("id", "call_text_" + idx++);
                call.addProperty("type", "function");
                JsonObject func = new JsonObject();
                func.addProperty("name", name);
                // Only extract the first valid tool call — forces one-at-a-time execution
                // so the model sees each result before deciding the next action.
                func.addProperty("arguments", argsStr);
                call.add("function", func);
                calls.add(call);
                break; // one at a time — model must see the result before acting again
            } catch (Exception ignored) { /* not valid JSON — skip */ }
        }
        // Bare-JSON fallback: some models (e.g. Llama via LM Studio) output the tool call
        // as a raw JSON object in content with no code fence at all.
        if (calls.isEmpty()) {
            String trimmed = content.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
                    // Require "type":"function" — the Llama-native tool-call marker.
                    // This prevents misinterpreting legitimate explanatory JSON as a call.
                    boolean isToolCall = obj.has("type")
                            && "function".equals(obj.get("type").getAsString());
                    if (isToolCall && obj.has("name")) {
                        String name = obj.get("name").getAsString();
                        if (toolMap.containsKey(name)) {
                            JsonElement argsEl = obj.has("parameters") ? obj.get("parameters")
                                               : obj.has("arguments")  ? obj.get("arguments")
                                               : new JsonObject();
                            String argsStr = argsEl.isJsonObject() ? argsEl.getAsJsonObject().toString() : "{}";
                            JsonObject call = new JsonObject();
                            call.addProperty("id", "call_text_" + idx);
                            call.addProperty("type", "function");
                            JsonObject func = new JsonObject();
                            func.addProperty("name", name);
                            func.addProperty("arguments", argsStr);
                            call.add("function", func);
                            calls.add(call);
                        }
                    }
                } catch (Exception ignored) { /* not valid JSON — skip */ }
            }
        }

        return !calls.isEmpty() ? calls : null;
    }

    /**
     * Convenience overload that accepts a simple {@link Consumer}&lt;String&gt; for
     * callers that only care about status text (e.g. tests).
     */
    public String chat(String userMessage, Consumer<String> statusCallback) {
        return chat(userMessage, new AgentCallback() {
            @Override public void onStatus(String m)                          { statusCallback.accept(m); }
            @Override public void onThinking(String t)                        { /* ignored */ }
            @Override public void onToolStart(String n, String d)             { statusCallback.accept(d + "…"); }
            @Override public void onToolEnd(String n, boolean ok)             { statusCallback.accept(ok ? "✅ " + n : "❌ " + n); }
        });
    }

    /**
     * Returns the exact JSON payload that would be sent to the API as the messages array.
     * If no conversation has started yet, builds the system message from current state.
     */
    public String getFullContextDump() {
        List<ChatMessage> msgs = history.getMessages();

        // Nothing sent yet — synthesise the system message so the viewer is useful immediately
        if (msgs.stream().noneMatch(m -> m.getRole() != ChatMessage.Role.SYSTEM) && msgs.isEmpty()) {
            String contextBlock = ContextManager.getInstance(project).buildContextBlock();
            String effectiveSysPmt = modePrompt.isBlank() ? baseSysPmt : modePrompt + "\n\n" + baseSysPmt;
            String sysPmt = contextBlock.isBlank() ? effectiveSysPmt : effectiveSysPmt + "\n\n" + contextBlock;
            JsonArray arr = new JsonArray();
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", sysPmt);
            arr.add(sys);
            return new GsonBuilder().setPrettyPrinting().create().toJson(arr);
        }

        JsonArray arr = new JsonArray();
        for (ChatMessage msg : msgs) arr.add(msg.toJson());
        return new GsonBuilder().setPrettyPrinting().create().toJson(arr);
    }

    /** Replays saved user/assistant messages back into the conversation history on load. */
    public void reloadHistory(List<copilot.CopilotSettings.SavedMessage> messages) {
        for (copilot.CopilotSettings.SavedMessage m : messages) {
            switch (m.role) {
                case "user"      -> history.add(ChatMessage.user(m.content));
                case "assistant" -> history.add(ChatMessage.assistant(m.content));
            }
        }
    }

    // ------------------------------------------------------------------
    // System-prompt sections  (public so UI can read the defaults)
    // ------------------------------------------------------------------

    /** Intro + hard rules + tool list — never user-editable. */
    public static String staticPromptSection() {
        return """
                You are Kalynx Copilot, an expert AI coding assistant embedded in IntelliJ IDEA.
                Use the tools below to read files, modify code, search the project, and run builds.

                ## Hard rules — never violate
                1. Before ANY tool call, output: `[PHASE] Target: <what>. Expect: <outcome>.`
                2. Never call compile_project without first making a code change this iteration.
                3. Never call finish_task unless the last compile/test output confirms success.
                4. If stuck with no new ideas: stop and ask the user — do not loop.

                ## Known failure modes
                | Name | Trigger | Required response |
                |---|---|---|
                | LOOP | Same tool + args called twice | Output `[LOOP DETECTED]` — re-state request, re-diagnose |
                | DRIFT | Lost track of original request | Re-read your anchor sentence from ANALYSE |
                | BLIND_COMPILE | compile_project with no prior change | Forbidden |
                | STALE_READ | Used history snapshot instead of ## section | Read ## sections only — never history |
                | PREMATURE_FINISH | finish_task before tests confirm success | Forbidden |

                ## Tools
                - get_current_file    — active editor file (auto-pins it)
                - list_files          — project directory tree
                - add_to_context      — pin file/folder; content appears in ## sections next turn
                - remove_from_context — unpin a path (call list_context first if path is uncertain)
                - list_context        — list all currently pinned paths
                - clear_context       — unpin everything (use when switching tasks)
                - replace_in_file     — targeted find-and-replace; auto-pins the file after each call
                - create_file         — create a new file with content
                - create_plan         — store a resolution plan and clear all pins
                - update_plan         — advance milestone status or tick exit-criteria checkboxes
                - search_in_files     — regex/substring search across all project files
                - scan_problems       — IntelliJ inspections across source files; returns file:line errors
                - get_problems        — live errors/warnings for open files only (faster, limited scope)
                - compile_project     — compile; returns errors with exact file:line references
                - run_tests           — run tests (Maven/Gradle auto-detected); accepts class/method filter
                - complete_phase      — signal phase completion; plugin validates and transitions state
                - remember            — store a durable fact in project memory (recalled in future sessions)
                - ask_user            — stop and ask the user a structured question (required when stuck ≥3 verifies)
                - update_scratchpad   — write a persistent note to yourself (max 500 chars, survives compression)
                - finish_task         — end the agent loop (requires: milestones COMPLETE + test evidence)""";
    }

    public static String defaultDynamicContextSection() {
        return """
                ## Context rules
                - Pinned file content appears in the `##` sections of THIS system message — not in tool results.
                - `##` content is re-read from disk every turn and is always current. History snapshots are stale — never use them.
                - Never construct `old_code` from memory or prior turns — copy exact text from the relevant `##` section only.
                - Same filename in multiple directories: always use the full relative path to disambiguate.
                - `remove_from_context`: call `list_context` first if the exact path is uncertain.
                - `clear_context`: use when switching to a new task so stale context does not carry over.""";
    }

    public static String defaultAgenticWorkflowSection() {
        return """
                ## Workflow

                **Anchor** — At the start of ANALYSE, output one sentence: "The user wants me to [restate request]." Return to it if you feel lost.
                Declare your phase at the start of each response: `Phase: RESOLVE / Milestone 2`

                ### ANALYSE
                Pin and read files broadly to understand the problem. Do not make code changes yet.

                ### PLAN
                Call `create_plan` once you understand the problem. Structure:
                - `# Technical Spike: ...` title; `**Objective:**`; `**Progress:** 0/N milestones`
                - Each milestone: `## Milestone N: title`, **Status**, **Objective**, **Relevant files**, **Exit criteria** (`- [ ]` checkboxes)
                - `---` between milestones

                ### RESOLVE
                For each milestone in order:
                1. `update_plan` → IN PROGRESS
                2. Pin milestone files (`add_to_context`)
                3. Inner loop (below) for each change
                4. Tick all exit criteria → `update_plan` → COMPLETE → unpin files

                ### FINISH
                All milestones COMPLETE and tests pass → call `finish_task`.

                ---

                ## Inner loop: SETUP → DETERMINE → ACT → TEST → REASON

                **SETUP** — Output `[SETUP] Target: <exact thing to read>. Expect: <what you expect to see>.`
                Read the section you are about to modify. Never act on assumptions.

                **DETERMINE** — Output `[DETERMINE] Root cause: <X>. Change: <what> in <file> because <why>.`
                If you cannot fill every field specifically, read more context first.

                **ACT** — Output `[ACT] Applying: <one-line description>.`
                Make exactly one change: `replace_in_file` or `create_file`.

                **TEST** — Output `[TEST] Verifying: <what this should confirm>.`
                Compile or run the relevant test. Read the full output.

                **REASON** — Answer all before proceeding:
                - Root cause addressed? [yes/no — why]
                - Exit criterion met? [yes/no — which / what remains]
                - Regression possible? [yes/no — where]
                ▶ **NEXT:** [SETUP | DETERMINE | ACT | TEST | MILESTONE COMPLETE | finish_task | ASK USER] — because [one sentence].

                > If the test FAILED: complete REASON before making another change. Never go TEST → ACT directly.""";
    }

    public static String defaultGuidelinesSection() {
        return """
                ## Guidelines
                - Non-source files (docs, notes, design) → `Documentation/` at the project root. Never write them into `src/`, `java/`, or `resources/`.
                - `replace_in_file` failure (old_code not found): read the `##` section for that file, copy exact current text, retry once. Never guess — wrong `old_code` causes infinite loops.
                - After every `replace_in_file` (success or failure), the file is auto-pinned. Check the `##` section to verify the change before any further action.""";
    }

    // ------------------------------------------------------------------
    // Phase workflow blocks  (one per phase, pushed each turn)
    // ------------------------------------------------------------------

    private static String buildPhaseWorkflowBlock(Phase phase) {
        return switch (phase) {
            case ANALYSE  -> phaseBlockAnalyse();
            case PLAN     -> phaseBlockPlan();
            case IMPLEMENT -> phaseBlockImplement();
            case VERIFY   -> phaseBlockVerify();
            case DONE     -> phaseBlockDone();
        };
    }

    private static String phaseBlockAnalyse() {
        return """
                ## Phase: ANALYSE

                Your first task is to understand the problem.

                **Anchor:** Output one sentence before any tool call: "The user wants me to [restate request]."

                Explore using add_to_context, search_in_files, get_current_file, list_files.
                Do not make any code changes yet.

                When you understand the problem: call `complete_phase(current_phase="ANALYSE")`.
                The system message will update to PLAN phase instructions on the next turn.""";
    }

    private static String phaseBlockPlan() {
        return """
                ## Phase: PLAN

                You understand the problem. Create a resolution plan with `create_plan`.

                Required structure:
                - `# Technical Spike: [short title]`
                - `**Objective:**` one or two sentences
                - `**Progress:** 0/N milestones complete`
                - Each milestone: `## Milestone N: title`, **Status** (NOT STARTED), **Objective**,
                  **Relevant files**, **Exit criteria** (`- [ ]` checkboxes)

                When done: call `complete_phase(current_phase="PLAN")`.""";
    }

    private static String phaseBlockImplement() {
        return """
                ## Phase: IMPLEMENT

                Work through milestones. Use the inner loop for every change:

                **SETUP** — `[SETUP] Target: <exact thing to read>. Expect: <what you expect>.`
                Read the section you are about to modify. Never act on assumptions.

                **DETERMINE** — `[DETERMINE] Root cause: <X>. Change: <what> in <file> because <why>.`
                If you cannot fill every field specifically, read more first.

                **ACT** — `[ACT] Applying: <one-line description>.`
                Make exactly one change: `replace_in_file` or `create_file`.

                **TEST** — Compile or run the relevant test immediately.

                **REASON** — Answer all before proceeding:
                - Root cause addressed? [yes/no — why]
                - Exit criterion met? [yes/no — which / what remains]
                - Regression possible? [yes/no — where]
                ▶ **NEXT:** [SETUP | DETERMINE | ACT | TEST | complete_phase | ASK USER]

                When ready to verify: call `complete_phase(current_phase="IMPLEMENT")`.
                If stuck / re-diagnosis needed: call `complete_phase(current_phase="IMPLEMENT", next_phase="ANALYSE")`.""";
    }

    private static String phaseBlockVerify() {
        return """
                ## Phase: VERIFY

                Run `compile_project` or `run_tests` to verify your changes. Read the full output.

                **If tests pass:**
                - Update the plan: mark milestones COMPLETE, tick exit-criteria checkboxes
                - More milestones remain → `complete_phase(current_phase="VERIFY", next_phase="IMPLEMENT")`
                - All milestones complete → `complete_phase(current_phase="VERIFY", next_phase="DONE")`

                **If tests fail:**
                - Analyse failures before making any change
                - `complete_phase(current_phase="VERIFY", next_phase="IMPLEMENT")` to return to fixing

                The DONE gate is hard: a passing verify must have run after the last code change.
                No testable output (docs-only, config change)? Provide `waive_reason`.""";
    }

    private static String phaseBlockDone() {
        return """
                ## Phase: DONE

                All milestones complete and tests pass.
                Call `finish_task` with a summary and the exact evidence line from the last verify output.""";
    }

    // ------------------------------------------------------------------
    // System-message assembly  (called every turn)
    // ------------------------------------------------------------------

    /**
     * Returns the stable, cacheable prefix of the system message (#6).
     * Recomputed only when phase or sections change; byte-identical otherwise
     * to enable prompt-cache hits in OpenAI-compatible APIs.
     */
    private String getStablePrefix(Phase phase) {
        String effectiveMode = modePrompt != null ? modePrompt : "";
        if (!phase.equals(cachedPhase)
                || !effectiveMode.equals(cachedModePrompt != null ? cachedModePrompt : "")
                || cachedStablePrefix == null) {
            String basePrompt;
            if (sectionDynamicContext == null) {
                basePrompt = baseSysPmt;
            } else {
                basePrompt = staticPromptSection()
                        + "\n\n" + sectionDynamicContext
                        + "\n\n" + buildPhaseWorkflowBlock(phase)
                        + "\n\n" + sectionGuidelines;
            }
            cachedStablePrefix = effectiveMode.isBlank() ? basePrompt : effectiveMode + "\n\n" + basePrompt;
            cachedPhase        = phase;
            cachedModePrompt   = effectiveMode;
        }
        return cachedStablePrefix;
    }

    /**
     * Reads the IDE daemon's known highlights for the last-edited file (#2).
     * Fast (reads cached analysis), safe to call from any thread via ReadAction.
     * Returns empty string if no file has been edited or no errors are known yet.
     */
    private String buildInlineProblemsSection(PhaseController pc) {
        String relPath = pc.getLastEditedRelPath();
        if (relPath == null || relPath.isBlank()) return "";
        String basePath = project.getBasePath();
        if (basePath == null) return "";
        try {
            return com.intellij.openapi.application.ApplicationManager.getApplication()
                    .runReadAction((com.intellij.openapi.util.Computable<String>) () -> {
                String fullPath = (basePath + "/" + relPath).replace("\\", "/");
                com.intellij.openapi.vfs.VirtualFile vf =
                        com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(fullPath);
                if (vf == null) return "";
                com.intellij.openapi.editor.Document doc =
                        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
                if (doc == null) return "";
                java.util.List<com.intellij.codeInsight.daemon.impl.HighlightInfo> highlights =
                        com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl.getHighlights(doc, null, project);
                if (highlights == null || highlights.isEmpty()) return "";
                java.util.List<String> errors   = new java.util.ArrayList<>();
                java.util.List<String> warnings = new java.util.ArrayList<>();
                for (com.intellij.codeInsight.daemon.impl.HighlightInfo info : highlights) {
                    if (info.getSeverity().compareTo(
                            com.intellij.lang.annotation.HighlightSeverity.WARNING) < 0) continue;
                    String desc = info.getDescription();
                    if (desc == null || desc.isBlank()) continue;
                    int line = doc.getLineNumber(info.getStartOffset()) + 1;
                    String entry = relPath + ":" + line + " — " + desc;
                    if (info.getSeverity().compareTo(
                            com.intellij.lang.annotation.HighlightSeverity.ERROR) >= 0) {
                        errors.add("[ERROR] " + entry);
                    } else {
                        warnings.add("[WARN] " + entry);
                    }
                }
                if (errors.isEmpty() && warnings.isEmpty()) return "";
                StringBuilder sb = new StringBuilder("=== IDE: " + relPath + " ===\n");
                errors.forEach(e   -> sb.append(e).append('\n'));
                warnings.forEach(w -> sb.append(w).append('\n'));
                sb.append("===");
                return sb.toString();
            });
        } catch (Exception ignored) {
            return "";
        }
    }

    private String buildFullSystemMessage(ContextManager cm, PhaseController pc) {
        // Manifest at top (primacy) — includes budget (#5)
        String manifestHeader = pc.buildManifestHeader(project.getBasePath(), currentIterations, currentTokens);

        // Stable prefix: byte-identical across turns when nothing changed (#6)
        String effectiveBase = getStablePrefix(pc.getPhase());

        // Model scratchpad — persistent working note (#7)
        String scratchpad = cm.getScratchpadNote();
        String scratchpadBlock = (scratchpad != null && !scratchpad.isBlank())
                ? "\n\n# Model Scratchpad\n" + scratchpad : "";

        // Active plan
        String plan = cm.getCurrentPlan();
        String planBlock = (plan != null && !plan.isBlank())
                ? "\n\n# Active Resolution Plan\n"
                  + "Work through each milestone in order. "
                  + "Use update_plan to advance status and tick exit-criteria checkboxes.\n\n" + plan
                : "";

        // Recalled memory (before pinned files)
        String memBlock = pc.getMemoryStore().buildMemoryBlock(cm.getAIPinnedPaths());
        String memSuffix = memBlock.isBlank() ? "" : "\n\n" + memBlock;

        // Pinned file context
        String contextBlock = cm.buildContextBlock();

        // Stuck nudge at bottom (recency) (#3)
        String stuckNudge  = pc.buildStuckNudge();
        String nudgeSuffix = stuckNudge.isBlank() ? "" : "\n\n" + stuckNudge;

        // IDE inline validation for last edited file — bottom of context (#2)
        String inlineProblems = buildInlineProblemsSection(pc);
        String inlineSuffix   = inlineProblems.isBlank() ? "" : "\n\n" + inlineProblems;

        String assembled = manifestHeader + "\n\n" + effectiveBase + scratchpadBlock + planBlock + memSuffix;
        return contextBlock.isBlank()
                ? assembled + nudgeSuffix + inlineSuffix
                : assembled + "\n\n" + contextBlock + nudgeSuffix + inlineSuffix;
    }

    // ------------------------------------------------------------------
    // Memory extraction before compression
    // ------------------------------------------------------------------

    private void extractFactsBeforeCompression() {
        StringBuilder conv = new StringBuilder();
        for (ChatMessage msg : history.snapshot()) {
            ChatMessage.Role role = msg.getRole();
            if (role == ChatMessage.Role.SYSTEM || role == ChatMessage.Role.TOOL) continue;
            if (msg.hasToolCalls()) continue;
            String c = msg.getContent();
            if (c == null || c.isBlank()) continue;
            conv.append("[").append(role.name().toLowerCase()).append("]\n")
                .append(c).append("\n\n");
        }
        if (conv.length() < 300) return; // not enough content to extract from

        String prompt =
            "Extract durable facts from this conversation for long-term project memory.\n"
          + "Include: architectural decisions, discovered constraints, API/library quirks, "
          + "root causes of bugs, gotchas that would surprise a new developer.\n"
          + "Exclude: step-by-step task details, standard boilerplate, already-resolved steps.\n"
          + "Return ONLY a JSON array: "
          + "[{\"text\":\"one specific sentence\",\"fileTags\":[\"path/To.java\"]}]\n"
          + "Maximum 8 facts. Only include things worth knowing in a brand-new session. "
          + "Raw JSON only — no markdown wrapper.\n\n"
          + "Conversation:\n\n"
          + conv.substring(0, Math.min(conv.length(), 8000));

        try {
            CopilotSettings s = CopilotSettings.getInstance();
            String raw = sendCompressionRequest(s.getApiEndpoint(), s.getApiKey(),
                    buildCompressionRequest(s.getModel(), prompt));
            if (raw == null || raw.isBlank()) return;

            String trimmed = raw.trim();
            if (trimmed.startsWith("```")) {
                trimmed = trimmed.replaceFirst("^```(?:json)?\\s*\\r?\\n?", "")
                                 .replaceFirst("\\r?\\n?```\\s*$", "").trim();
            }

            JsonArray arr = JsonParser.parseString(trimmed).getAsJsonArray();
            ContextManager cm  = ContextManager.getInstance(project);
            copilot.memory.MemoryStore store = cm.getPhaseController().getMemoryStore();
            String phase = cm.getCurrentPhase() != null ? cm.getCurrentPhase().name() : "ANALYSE";

            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String text = obj.has("text") ? obj.get("text").getAsString() : "";
                java.util.List<String> tags = new java.util.ArrayList<>();
                if (obj.has("fileTags") && obj.get("fileTags").isJsonArray()) {
                    for (JsonElement tag : obj.getAsJsonArray("fileTags"))
                        tags.add(tag.getAsString());
                }
                if (!text.isBlank()) store.addOrUpdate(text, tags, phase, "plugin:compression");
            }
        } catch (Exception ignored) {}
    }
}

