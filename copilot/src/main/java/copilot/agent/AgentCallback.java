package copilot.agent;

/**
 * Typed callback used by {@link AgentSession#chat} to report progress to the UI.
 *
 * <ul>
 *   <li>{@link #onStatus}     — brief status text for the spinner label</li>
 *   <li>{@link #onThinking}   — chain-of-thought / narration content</li>
 *   <li>{@link #onToolStart}  — a tool is about to execute</li>
 *   <li>{@link #onToolEnd}    — a tool finished executing</li>
 *   <li>{@link #onUsage}      — token usage after each LLM call</li>
 * </ul>
 */
public interface AgentCallback {

    /** Update the spinner/status label with a short message. */
    void onStatus(String message);

    /** The model emitted reasoning/narration text. May be multi-line. */
    void onThinking(String thought);

    /** Called for each live reasoning token as it streams in. */
    default void onThinkingChunk(String chunk) {}

    /** Called once when the reasoning stream is complete for this turn. */
    default void onThinkingStreamEnd() {}

    /**
     * A tool is about to be executed.
     *
     * @param toolName    raw snake_case tool name (e.g. {@code read_file})
     * @param description human-readable description (e.g. {@code "Reading src/Main.java"})
     */
    void onToolStart(String toolName, String description);

    /**
     * A tool finished executing.
     *
     * @param toolName raw snake_case tool name
     * @param success  {@code true} if the tool executed without throwing
     */
    void onToolEnd(String toolName, boolean success);

    /**
     * A tool returned an error result (started with "Error:", threw an exception,
     * or referenced an unknown tool name). The full error text is provided so the
     * UI can display it directly — the AI alone cannot be relied on to relay it.
     *
     * @param toolName    raw snake_case tool name
     * @param errorMessage the full error string returned by the tool
     */
    default void onToolError(String toolName, String errorMessage) {}

    /**
     * Token usage reported by the API after this LLM call.
     *
     * @param promptTokens     tokens consumed by the conversation so far
     * @param completionTokens tokens in the model's reply
     * @param contextLength    max context window for this agent (0 = unknown)
     */
    default void onUsage(int promptTokens, int completionTokens, int contextLength) {}

    /**
     * A tool whose {@code shouldShowResultInChat()} returns {@code true} finished.
     * The full result string is provided so the UI can display it directly in the chat,
     * regardless of whether the tool succeeded or failed.
     *
     * @param toolName raw snake_case tool name
     * @param result   the full text returned by the tool
     */
    default void onToolResult(String toolName, String result) {}

    /**
     * Called for each streamed text chunk of the final assistant response.
     * The UI should append this to a live "typing" buffer.
     */
    default void onResponseChunk(String chunk) {}

    /**
     * Called when a streaming turn that emitted {@link #onResponseChunk} events
     * turns out to have been a tool-call narration, not the final response.
     * The UI should clear its live buffer (the content will follow via {@link #onThinking}).
     */
    default void onResponseAborted() {}
}



