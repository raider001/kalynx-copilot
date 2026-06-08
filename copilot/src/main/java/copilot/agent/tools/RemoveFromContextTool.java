package copilot.agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.context.ContextManager;
import copilot.tools.api.AgentTool;

/**
 * Unpins a file or folder from the AI dynamic context.
 * The stripped snapshot is deleted and will no longer appear in the system prompt.
 */
public class RemoveFromContextTool implements AgentTool {

    @Override
    public String getName() {
        return "remove_from_context";
    }

    @Override
    public String getDescription() {
        return "Unpin a file or folder from your persistent dynamic context. "
             + "The stripped snapshot will no longer be injected into the system prompt. "
             + "Use this when you have finished working on a file and no longer need "
             + "structural awareness of it across turns. "
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
        return "Unpinning from context: " + (args.has("path") ? args.get("path").getAsString() : "");
    }

    @Override
    public String execute(JsonObject params, Project project) {
        String path = params.has("path") ? params.get("path").getAsString().trim() : "";
        if (path.isEmpty()) return "Error: path is required";
        return ContextManager.getInstance(project).removeEntryForAI(path);
    }
}
