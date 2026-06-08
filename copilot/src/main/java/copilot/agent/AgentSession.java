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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                        new copilot.agent.tools.ScanProblemsTool()
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

            // --- Model wants to call tools ---
            if (message.has("tool_calls")) {
                // The streamed content was narration (e.g. "I'll look at the files…"), not the
                // final response. Tell the UI to reclassify it as a thinking block.
                callback.onResponseAborted();
                String narration = streamedContent.toString().trim();
                narration = Pattern.compile("(?s)<think>.*?</think>").matcher(narration).replaceAll("").trim();
                if (!narration.isEmpty()) callback.onThinking(narration);

                JsonArray toolCalls = message.getAsJsonArray("tool_calls");
                history.add(ChatMessage.assistantWithToolCalls(toolCalls));

                for (JsonElement tcElem : toolCalls) {
                    JsonObject tc       = tcElem.getAsJsonObject();
                    String callId       = tc.get("id").getAsString();
                    String toolName     = tc.getAsJsonObject("function").get("name").getAsString();
                    String argsStr      = tc.getAsJsonObject("function").get("arguments").getAsString();
                    JsonObject args     = JsonParser.parseString(argsStr).getAsJsonObject();

                    AgentTool tool = toolMap.get(toolName);
                    String description = tool != null ? tool.getStatusMessage(args) : toolName;
                    callback.onToolStart(toolName, description);

                    String toolResult;
                    boolean success = true;
                    try {
                        if (tool == null) {
                            toolResult = "Error: unknown tool \"" + toolName + "\"";
                            success = false;
                        } else {
                            toolResult = tool.execute(args, project);
                            if (toolResult != null && toolResult.startsWith("Error:")) {
                                success = false;
                            }
                        }
                    } catch (Exception e) {
                        toolResult = "Error executing " + toolName + ": " + e.getMessage();
                        success = false;
                    }

                    history.add(ChatMessage.toolResult(callId, toolName, toolResult));
                    callback.onToolEnd(toolName, success);
                    if (!success) callback.onToolError(toolName, toolResult);
                    if (tool != null && tool.shouldShowResultInChat())
                        callback.onToolResult(toolName, toolResult);
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
                String rawContent = streamedContent.toString();

                // Strip any <think> tags that weren't caught by the reasoning callback
                Pattern thinkPattern = Pattern.compile("(?s)<think>(.*?)</think>");
                String content = thinkPattern.matcher(rawContent).replaceAll("").trim();

                if (!content.isEmpty()) {
                    history.add(ChatMessage.assistant(content));
                    if (!CopilotSettings.getInstance().retainToolHistory)
                        history.pruneToolMessages();
                    return content;
                }

                // Model returned only thinking content — prompt for a visible reply.
                if (!rawContent.isEmpty()) history.add(ChatMessage.assistant(rawContent));
                history.add(ChatMessage.user(
                        "Please write your final reply to the user now. " +
                        "Summarise what you did and provide any relevant information."));
            }
        }

        return "⚠️ Agent reached the maximum iteration limit without producing a final answer.";
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
                                        only available if a build.gradle / build.gradle.kts exists""";
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
                Agentic workflow — follow these phases for any multi-file task:
                1. ANALYSE: Pin and read the files you need to understand the problem.
                   Read broadly. You may pin multiple files during this phase.
                2. PLAN: Once you understand the problem, call create_plan with a structured
                   Markdown plan using this layout:
                   - "# Technical Spike: ..." title
                   - "**Objective:**" summary and "**Progress:** 0 / N milestones complete"
                   - Each milestone as "## Milestone N: title", then "**Status:** NOT STARTED",
                     "**Objective:**", "**Relevant files:**", "**Context:**",
                     "**Exit criteria:**" checkboxes (- [ ] items)
                   - "---" horizontal rule between each milestone
                   This clears all pinned files and locks in the plan. Do not skip this step.
                3. RESOLVE: Work through each milestone in order:
                   a. Call update_plan to set the milestone to IN PROGRESS
                   b. Pin only the files listed for that milestone (add_to_context)
                   c. Make the required changes (replace_in_file or create_file)
                   d. Verify exit criteria (compile_project, tests as needed)
                   e. Call update_plan to tick checkboxes and set milestone to COMPLETE
                   f. Unpin milestone files, then move to the next milestone
                4. TEST: Run compile_project after all milestones are COMPLETE.
                   If there are errors, pin only the failing file, fix it, recompile.
                   Repeat until BUILD SUCCESSFUL.""";
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
                - When compilation fails, read every error carefully — each one includes the
                  file path and line number. Fix the root cause in the relevant file(s), then
                  run compile_project again to confirm the fix.
                - Keep fixing and recompiling until BUILD SUCCESSFUL before stopping.""";
    }
}

