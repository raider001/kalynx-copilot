package copilot;

/**
 * Configuration for a single AI agent.
 * Public fields are used so IntelliJ's XmlSerializer can persist them inside the
 * {@link CopilotSettings} component without any extra annotations.
 */
public class AgentConfig {

    /** Display name shown in the dropdown and settings list. */
    public String name = "Default";

    /** Full URL of the chat-completions endpoint. */
    public String apiEndpoint = "https://api.openai.com/v1/chat/completions";

    /** Bearer token — may be empty for local Ollama / LM Studio. */
    public String apiKey = "";

    /** Model identifier (e.g. {@code gpt-4o}, {@code qwen3-coder-next}). */
    public String model = "gpt-4o";

    /** Optional system prompt. Falls back to the built-in default when blank. */
    public String systemPrompt = "";

    /** Maximum number of agentic loop iterations before giving up. */
    public int maxIterations = 100;

    /**
     * Maximum tokens the model may generate per response.
     * Raise this for reasoning/thinking models whose chain-of-thought alone
     * can exceed the default. 16384 is a safe default; set to 32768+ for
     * models like QwQ or DeepSeek-R1.
     */
    public int maxOutputTokens = 16384;

    /**
     * Wall-clock timeout in seconds for a single LLM response (reasoning + content).
     * When exceeded the request is cancelled and a clear error is returned.
     * Set to 0 to disable (unlimited). Default: 300 (5 minutes).
     */
    public int requestTimeoutSeconds = 300;

    /** Required by IntelliJ's XML serializer. */
    public AgentConfig() {}

    /** Convenience constructor for tests / defaults. */
    public AgentConfig(String name, String apiEndpoint, String apiKey, String model, String systemPrompt) {
        this.name = name;
        this.apiEndpoint = apiEndpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.systemPrompt = systemPrompt;
    }

    /** Returns a deep copy of this config. */
    public AgentConfig copy() {
        AgentConfig c = new AgentConfig(name, apiEndpoint, apiKey, model, systemPrompt);
        c.maxIterations = maxIterations;
        c.maxOutputTokens = maxOutputTokens;
        c.requestTimeoutSeconds = requestTimeoutSeconds;
        return c;
    }

    /** Used by JList renderer. */
    @Override
    public String toString() {
        return name;
    }

    public boolean isValid() {
        return apiEndpoint != null && !apiEndpoint.trim().isEmpty()
                && model != null && !model.trim().isEmpty();
    }
}


