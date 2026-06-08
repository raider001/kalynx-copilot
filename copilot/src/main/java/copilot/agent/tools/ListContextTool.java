package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.context.ContextManager;
import copilot.tools.api.AgentTool;

/**
 * Returns the exact relative paths of all AI-pinned context entries.
 * Use this when you need the precise path to pass to remove_from_context.
 */
public class ListContextTool implements AgentTool {

    @Override
    public String getName() { return "list_context"; }

    @Override
    public String getDescription() {
        return "List all files and folders currently pinned in your dynamic context. "
             + "Returns exact relative paths so you can pass them to remove_from_context. "
             + "Call this when you are unsure of the exact pinned path before unpinning.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) { return "Listing pinned context"; }

    @Override
    public String execute(JsonObject params, Project project) {
        return ContextManager.getInstance(project).listAIEntries();
    }
}
