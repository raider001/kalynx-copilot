package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.context.ContextManager;
import copilot.tools.api.AgentTool;

/**
 * Writes a persistent note that survives conversation compression.
 * Single slot, overwrite (never append), capped at 500 chars.
 */
public class UpdateScratchpadTool implements AgentTool {

    private static final int MAX_CHARS = 500;

    @Override public String getName() { return "update_scratchpad"; }
    @Override public boolean shouldShowResultInChat() { return false; }

    @Override
    public String getDescription() {
        return "Write a persistent note to yourself that survives compression (max 500 chars). " +
               "Use it to capture a key hypothesis, decision, or current working theory. " +
               "Overwrites the previous note — do not append. " +
               "Call with an empty note to clear. " +
               "Appears near the top of the system message every turn.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject noteProp = new JsonObject();
        noteProp.addProperty("type", "string");
        noteProp.addProperty("description",
                "Your note (max 500 chars). Completely overwrites the previous note. " +
                "Pass empty string to clear.");
        props.add("note", noteProp);
        schema.add("properties", props);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("note");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) {
        return "Updating scratchpad";
    }

    @Override
    public String execute(JsonObject params, Project project) {
        String note = params.has("note") ? params.get("note").getAsString() : "";
        if (note.length() > MAX_CHARS) note = note.substring(0, MAX_CHARS);
        note = note.strip();
        ContextManager.getInstance(project).setScratchpadNote(note.isEmpty() ? null : note);
        if (note.isEmpty()) return "Scratchpad cleared.";
        return "Scratchpad updated (" + note.length() + " chars). Visible in system message next turn.";
    }
}
