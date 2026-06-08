package copilot.agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.context.ContextManager;
import copilot.tools.api.AgentTool;

/**
 * Pins a file or folder to the AI dynamic context.
 * A stripped (public-signature) snapshot is written to
 * {@code .kalynx-context/dynamic-context/stripped/} and injected into
 * the system prompt for all subsequent turns.
 */
public class AddToContextTool implements AgentTool {

    @Override
    public String getName() {
        return "add_to_context";
    }

    @Override
    public String getDescription() {
        return "Pin a project file or folder to your dynamic context. "
             + "Its current content will appear in the ## section of the system message "
             + "on the very next turn — do not expect it in this tool result. "
             + "The content is re-read from disk on every subsequent turn so it is always current. "
             + "Call remove_from_context when the file is no longer needed. "
             + "Path must be relative to the project root (e.g. src/main/Foo.java).";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "Path relative to the project root");
        props.add("path", path);
        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("path");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) {
        return "Pinning to context: " + (args.has("path") ? args.get("path").getAsString() : "");
    }

    @Override
    public String execute(JsonObject params, Project project) {
        String path = params.has("path") ? params.get("path").getAsString().trim() : "";
        if (path.isEmpty()) return "Error: path is required";
        return ContextManager.getInstance(project).addEntryForAI(path);
    }
}
