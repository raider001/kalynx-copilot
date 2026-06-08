package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.context.ContextManager;
import copilot.tools.api.AgentTool;

/**
 * Updates the active resolution plan in place — used by the agent to advance
 * milestone statuses (NOT STARTED → IN PROGRESS → COMPLETE) and tick off
 * exit-criteria checkboxes as work progresses.
 *
 * Unlike create_plan, this does NOT clear pinned files.
 */
public class UpdatePlanTool implements AgentTool {

    @Override public String getName() { return "update_plan"; }

    @Override
    public String getDescription() {
        return "Updates the active resolution plan in place. Use this to advance a milestone "
             + "status (NOT STARTED → IN PROGRESS → COMPLETE) or tick off an exit-criteria "
             + "checkbox (- [ ] → - [x]). Does NOT clear pinned files. "
             + "Requires an active plan created with create_plan.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject planProp = new JsonObject();
        planProp.addProperty("type", "string");
        planProp.addProperty("description",
                "The complete updated plan Markdown. Copy the current plan verbatim and "
              + "change only the status or checkboxes that have changed. "
              + "Milestone status values: NOT STARTED | IN PROGRESS | COMPLETE.");
        props.add("plan", planProp);

        schema.add("properties", props);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("plan");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) { return "Updating resolution plan"; }

    @Override
    public String execute(JsonObject params, Project project) {
        String plan = params.get("plan").getAsString().trim();
        if (plan.isEmpty()) return "Error: plan cannot be empty.";

        ContextManager cm = ContextManager.getInstance(project);
        if (cm.getCurrentPlan() == null || cm.getCurrentPlan().isBlank()) {
            return "Error: no active plan to update. Create a plan first with create_plan.";
        }
        cm.setCurrentPlan(plan);
        return "Plan updated.";
    }
}
