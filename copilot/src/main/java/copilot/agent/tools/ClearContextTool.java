package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.context.ContextManager;
import copilot.tools.api.AgentTool;

/**
 * Unpins all AI-pinned context entries at once.
 * Useful when switching tasks so stale context does not bleed into the next request.
 */
public class ClearContextTool implements AgentTool {

    @Override
    public String getName() { return "clear_context"; }

    @Override
    public String getDescription() {
        return "Unpin all files currently in your AI dynamic context at once. "
             + "Use this when switching to a completely different task to start with a clean slate. "
             + "User-added (non-AI) context entries are not affected.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) { return "Clearing pinned context"; }

    @Override
    public String execute(JsonObject params, Project project) {
        return ContextManager.getInstance(project).clearAIEntries();
    }
}
