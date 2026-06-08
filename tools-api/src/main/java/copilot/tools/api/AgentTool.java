package copilot.tools.api;

import com.google.gson.JsonObject;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;

/**
 * Contract for all tools the AI agent can call.
 *
 * <p>Register implementations via the {@code com.kalynx.copilot.agentTool} extension point
 * in {@code plugin.xml}. Third-party plugins can contribute their own tools by declaring
 * a dependency on {@code com.kalynx.copilot} and registering an {@code <agentTool>} extension.
 *
 * <pre>{@code
 * <extensions defaultExtensionNs="com.kalynx.copilot">
 *     <agentTool implementation="com.example.MyCustomTool"/>
 * </extensions>
 * }</pre>
 */
public interface AgentTool {

    ExtensionPointName<AgentTool> EP_NAME =
            ExtensionPointName.create("com.kalynx.copilot.agentTool");

    /** Unique snake_case name sent to the LLM (e.g. {@code read_file}). */
    String getName();

    /** Human-readable description of what this tool does, shown to the LLM. */
    String getDescription();

    /** JSON Schema object describing the tool's parameters. */
    JsonObject getParameterSchema();

    /**
     * Executes the tool and returns a plain-text result that is fed back to the LLM.
     *
     * @param params  the JSON arguments provided by the LLM
     * @param project the currently open IntelliJ project
     * @return result text (error messages should be returned as strings, not thrown)
     */
    String execute(JsonObject params, Project project) throws Exception;

    /** Short human-readable description of this specific invocation shown in the UI status bar. */
    default String getStatusMessage(JsonObject args) {
        return getName();
    }

    /**
     * Whether the tool's result should always be shown as a message in the chat,
     * regardless of success or failure. Return {@code true} for tools whose output
     * is directly meaningful to the user (e.g. build results, problem reports).
     * Defaults to {@code false} for file/context tools whose output is only for the AI.
     */
    default boolean shouldShowResultInChat() {
        return false;
    }

    /** Builds the OpenAI-compatible function definition object sent with each API request. */
    default JsonObject toToolDefinition() {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", getName());
        function.addProperty("description", getDescription());
        function.add("parameters", getParameterSchema());
        tool.add("function", function);
        return tool;
    }
}
