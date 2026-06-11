package copilot.agent.tools;

import com.google.gson.*;
import com.intellij.openapi.project.Project;
import copilot.context.ContextManager;
import copilot.tools.api.AgentTool;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores a durable fact in the project's semantic memory.
 * Facts are recalled in future sessions when the tagged files are pinned.
 */
public class RememberTool implements AgentTool {

    @Override public String getName()                 { return "remember"; }
    @Override public boolean shouldShowResultInChat() { return false; }

    @Override
    public String getDescription() {
        return "Store a durable fact in the project's semantic memory. "
             + "Use for non-obvious, non-derivable facts: architectural constraints, known gotchas, "
             + "API quirks, root causes discovered during debugging. "
             + "Do not store what is already visible in the code. "
             + "Optionally tag the fact to specific files so it is recalled when those files are pinned.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject fact = new JsonObject();
        fact.addProperty("type", "string");
        fact.addProperty("description", "One clear, specific sentence worth knowing in a future session.");
        props.add("fact", fact);

        JsonObject tags = new JsonObject();
        tags.addProperty("type", "array");
        tags.addProperty("description",
                "Optional file paths (relative to project root) this fact relates to. "
              + "The fact will be recalled when these files are pinned.");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        tags.add("items", items);
        props.add("file_tags", tags);

        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("fact");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) { return "Saving to memory"; }

    @Override
    public String execute(JsonObject params, Project project) {
        String fact = params.has("fact") ? params.get("fact").getAsString().trim() : "";
        if (fact.isEmpty()) return "Error: fact cannot be empty.";

        List<String> fileTags = new ArrayList<>();
        if (params.has("file_tags") && params.get("file_tags").isJsonArray()) {
            for (JsonElement el : params.getAsJsonArray("file_tags")) {
                String tag = el.getAsString().trim();
                if (!tag.isEmpty()) fileTags.add(tag);
            }
        }

        ContextManager cm = ContextManager.getInstance(project);
        String phase = cm.getCurrentPhase() != null ? cm.getCurrentPhase().name() : "ANALYSE";
        cm.getPhaseController().getMemoryStore().addOrUpdate(fact, fileTags, phase, "model");
        return "Remembered: " + fact;
    }
}
