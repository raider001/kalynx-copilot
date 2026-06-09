package copilot.agent;

import com.google.gson.*;
import com.intellij.openapi.project.Project;
import copilot.CopilotSettings;
import copilot.CopilotUtil;
import copilot.context.ContextManager;
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
            Set.of("replace_in_file", "create_file", "create_plan", "update_plan");
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
        baseSysPmt = assembleSystemPrompt();
    }

    public void setAgenticWorkflowSection(String text) {
        if (sectionAgenticWorkflow == null) return;
        sectionAgenticWorkflow = text;
        baseSysPmt = assembleSystemPrompt();
    }

    public void setGuidelinesSection(String text) {
        if (sectionGuidelines == null) return;
        sectionGuidelines = text;
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
                        new copilot.agent.tools.FinishTaskTool()
                ));
            }
            for (AgentTool t : tools) toolMap.put(t.getName(), t);
        }

        // Refresh system message so mode, context-file changes, etc. are picked up each turn
        ContextManager cm = ContextManager.getInstance(project);
        String contextBlock = cm.buildContextBlock();
        String effectiveSysPmt = modePrompt.isBlank()
                ? baseSysPmt
                : modePrompt + "\n\n" + baseSysPmt;

        String plan = cm.getCurrentPlan();
        String planBlock = (plan != null && !plan.isBlank())
                ? "\n\n# Active Resolution Plan\nYou are in the RESOLVE phase. " +
                  "Work through each milestone in order. " +
                  "Use update_plan to advance milestone status and tick exit-criteria checkboxes.\n\n" + plan
                : "";

        String fullSysPmt = effectiveSysPmt + planBlock;
        history.setSystemMessage(contextBlock.isBlank()
                ? fullSysPmt
                : fullSysPmt + "\n\n" + contextBlock);

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

        int iterations = 0;
        while (iterations < maxIterations) {
            if (cancelled) return "⏹ Request cancelled.";
            iterations++;

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
                    // Write tools reset the tracker; read-only tools are checked for duplicates.
                    String toolResult;
                    boolean success = true;
                    boolean isDuplicate = false;

                    if (WRITE_TOOLS.contains(toolName)) {
                        callsSinceLastWrite.clear();
                    } else {
                        String sig = toolName + " " + argsStr;
                        if (callsSinceLastWrite.contains(sig)) {
                            isDuplicate = true;
                        } else {
                            callsSinceLastWrite.add(sig);
                        }
                    }

                    if (isDuplicate) {
                        toolResult = "LOOP DETECTED: '" + toolName + "' was already called with these " +
                                     "exact arguments since your last code change — the result will be " +
                                     "identical to what you already received. Do not call it again.\n" +
                                     "You must make a code change before calling this tool again.\n" +
                                     "Required next action:\n" +
                                     "  • Identify the specific error from the output you already received.\n" +
                                     "  • Use replace_in_file to fix it, then re-run.\n" +
                                     "  • If you cannot determine how to fix it, stop and explain the problem to the user.\n" +
                                     "Do NOT call finish_task — a loop means the problem is not resolved.";
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
                String refreshedContext = cm.buildContextBlock();
                String refreshedSysPmt = modePrompt.isBlank() ? baseSysPmt : modePrompt + "\n\n" + baseSysPmt;
                String refreshedPlan = cm.getCurrentPlan();
                String refreshedPlanBlock = (refreshedPlan != null && !refreshedPlan.isBlank())
                        ? "\n\n# Active Resolution Plan\nYou are in the RESOLVE phase. " +
                          "Work through each step below in order. " +
                          "Pin only the file for the current step before editing.\n\n" + refreshedPlan
                        : "";
                String refreshedFull = refreshedSysPmt + refreshedPlanBlock;
                history.setSystemMessage(refreshedContext.isBlank()
                        ? refreshedFull
                        : refreshedFull + "\n\n" + refreshedContext);

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

    /** Intro + tool list — never user-editable. */
    public static String staticPromptSection() {
        return """
                You are Kalynx Copilot, an expert AI coding assistant embedded in IntelliJ IDEA.
                You help developers write, understand, refactor, debug, and build code.

                You have access to tools that let you read and modify files, search the project,
                and run build commands. Use them proactively to gather context before answering.

                Available tools:
                - get_current_file    — get the file currently open in the editor (auto-pins it)
                - list_files          — list the project directory tree
                - add_to_context      — pin a file or folder; its content appears in the
                                        ## section of THIS system message next turn —
                                        not in the tool result. Re-read from disk every turn.
                - remove_from_context — unpin a specific file or folder when no longer needed
                - list_context        — list exact paths of all currently pinned files
                                        (use before remove_from_context if unsure of the path)
                - clear_context       — unpin ALL AI-pinned files at once (for task switching)
                - replace_in_file     — apply a targeted find-and-replace edit to a file.
                - create_file         — create a new file with given content
                - create_plan         — transition from Analyse to Resolve: stores your
                                        structured Markdown resolution plan (visible every turn)
                                        and clears all pinned files. Call this once you understand
                                        the problem and are ready to start making changes.
                - update_plan         — update the active plan in place: advance a milestone
                                        status (NOT STARTED → IN PROGRESS → COMPLETE) or tick
                                        an exit-criteria checkbox (- [ ] → - [x]).
                                        Does NOT clear pinned files.
                - search_in_files     — search across all project files by regex or substring
                - scan_problems        — run IntelliJ's inspection engine across all source files
                                        (or a subdirectory) and return errors and warnings;
                                        works without opening files in the editor
                - get_problems         — quick read of live errors/warnings for files already
                                        open in the editor (faster but limited to open files)
                - compile_project      — compile the project using IntelliJ's built-in compiler;
                                        returns errors with exact file paths and line numbers;
                                        always available, no Gradle/Maven wrapper required
                - maven_build         — run Maven goals (test, package, install, etc.)
                                        only available if a pom.xml exists in the project root
                - gradle_build         — run Gradle tasks (test, build, etc.)
                                        only available if a build.gradle / build.gradle.kts exists
                - run_tests            — run unit tests using Maven or Gradle (auto-detected);
                                        accepts an optional 'filter' for a specific class or method:
                                        "MyClass", "MyClass#myMethod", or "com.example.*";
                                        returns a per-test pass/fail breakdown with stack traces
                - finish_task          — signal that the task is fully complete and end the loop.
                                        Call this ONLY when all milestones are COMPLETE, all tests
                                        pass, and there is nothing left to do. Provide a summary.
                                        Do NOT call it while there are still failures or open work.""";
    }

    public static String defaultDynamicContextSection() {
        return """
                Dynamic context management:
                - add_to_context (or equivalently read_file) pins a file. Its content does NOT
                  appear in the tool result — it appears in the ## sections of THIS system message
                  on the next turn, re-read live from disk. It is always current; never stale.
                - If a file is already shown in the ## sections above, you have its current
                  content — do not pin it again. Conversation history may contain old snapshots;
                  always prefer the ## section content over anything seen in prior turns.
                - Use remove_from_context to unpin individual files when you are done with them.
                  If unsure of the exact path, call list_context first to get the precise string.
                - Use clear_context when switching to a completely new task so stale context
                  does not bleed into the next request.
                - When multiple files share the same filename, use the full relative path
                  (e.g. src/foo/Server.java vs src/bar/Server.java) to identify which file
                  to edit. Never construct old_code from one file's section and apply it to
                  a different file's path.
                - Never construct old_code from memory or prior turns. It must come from the
                  content visible right now under the specific ## section you are targeting.""";
    }

    public static String defaultAgenticWorkflowSection() {
        return """
                Follow these phases for any multi-file task.

                ### 1. ANALYSE
                Pin and read the files you need to understand the problem. Read broadly — you may pin multiple files during this phase.

                ### 2. PLAN
                Once you understand the problem, call `create_plan` with a structured Markdown plan:
                - `# Technical Spike: ...` title
                - `**Objective:**` summary and `**Progress:** 0 / N milestones complete`
                - Each milestone as `## Milestone N: title` with **Status**, **Objective**, **Relevant files**, **Context**, and **Exit criteria** checkboxes (`- [ ] items`)
                - `---` horizontal rule between milestones

                This clears all pinned files and locks in the plan. **Do not skip this step.**

                ### 3. RESOLVE
                Work through each milestone in order:

                1. Call `update_plan` to set the milestone to **IN PROGRESS**
                2. Pin only the files listed for that milestone (`add_to_context`)
                3. For each individual change, follow the **inner loop** below
                4. Once all exit-criteria checkboxes are ticked, set milestone to **COMPLETE**
                5. Unpin milestone files, then move to the next milestone

                ---

                **Inner loop — repeat for each change within a milestone**

                **SETUP** — Read the exact section you are about to modify. Confirm the current state — never act on assumptions.

                **DETERMINE** — State the root cause this change addresses. What will you change, in which file, and why? If you cannot state the root cause clearly, read more context first.

                **ACT** — Make exactly one targeted change (`replace_in_file` or `create_file`).

                **TEST** — Compile or run the relevant test. Read the full output.

                **REASON** — Evaluate holistically before deciding what to do next:
                - Did this change fix the stated root cause?
                - Does it satisfy the relevant exit criterion, or is more work needed?
                - Could it have introduced a regression elsewhere? If yes, add an inner-loop iteration to verify.
                - Does it still align with the milestone objective and overall plan? If not, `update_plan` before continuing.
                - What is the single most important next action?

                > **If the test FAILED:** do not immediately make another change. Re-read the output and code, revise your root-cause diagnosis. **Never go TEST → ACT without passing through REASON.**

                ---

                ### 4. TEST
                Run `compile_project` after all milestones are COMPLETE. Apply the same inner loop to each fix.

                - Never call a build tool again without first making a code change — recompiling without changes always produces the same result.
                - If you cannot determine how to fix an error, **stop and ask the user**.

                ### 5. FINISH
                When all milestones are COMPLETE and all tests pass, call `finish_task` with a summary of what was done.
                Do NOT call `finish_task` before tests pass. Do NOT keep calling tools after the task is done.""";
    }

    public static String defaultGuidelinesSection() {
        return """
                Guidelines:
                - Be concise and precise. Favour working code over lengthy explanations.
                - When suggesting code changes, use replace_in_file or create_file to apply them directly.
                - Documentation, design documents, architecture notes, and any other non-source
                  files must be placed under Documentation/ at the project root (e.g.
                  Documentation/design/MyFeature.md). Never write docs or notes into a
                  source directory (src/, java/, resources/, etc.).
                - Always pin the relevant file(s) via add_to_context before suggesting changes.
                - When you create or edit a file, confirm what you did in your final response.
                - Never invent file contents — always pin and read first.
                - After EVERY replace_in_file call (success or failure), the file is
                  automatically pinned. Before doing anything else, read its current content
                  from the dynamic context above to confirm the actual state of the file.
                - If replace_in_file succeeds: verify the new_code is present in the pinned
                  content. If it is already there from a previous edit, do not apply it again.
                - If replace_in_file fails (old_code not found): read the pinned content,
                  find the exact text as it appears now, and retry once with the correct
                  old_code. Never guess or vary the text — that causes infinite loops.
                - After making ANY code changes (replace_in_file or create_file), you MUST
                  immediately run compile_project to verify the code compiles. Do NOT report
                  success before the build passes. Use gradle_build or maven_build only when
                  you need to run tests or produce build artefacts.
                - When compilation fails: each error tells you the exact file and line.
                  Pin that file, read it, make a targeted fix, then recompile.
                  Only call compile_project after you have changed something — recompiling
                  with no changes made will always produce the same errors.
                - If you cannot determine how to fix a build error (e.g. missing dependency,
                  unknown symbol, configuration issue), stop immediately and tell the user:
                  show them the exact error, explain what you tried, and ask for guidance.
                  Do not guess, do not retry the same build, do not loop.""";
    }
}

