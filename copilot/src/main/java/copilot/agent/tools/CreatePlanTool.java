package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.context.ContextManager;
import copilot.tools.api.AgentTool;

/**
 * Marks the transition from the Analyse phase to the Resolve phase.
 *
 * Calling this tool:
 *  1. Stores the structured plan so it appears in the system message every turn.
 *  2. Clears all AI-pinned files — analysis context is no longer needed.
 *
 * The agent then works through milestones one at a time, using update_plan to
 * advance each milestone status and tick off exit-criteria checkboxes.
 */
public class CreatePlanTool implements AgentTool {

    @Override public String getName() { return "create_plan"; }

    @Override
    public String getDescription() {
        return "Transitions from the Analyse phase to the Resolve phase. "
             + "Stores a structured resolution plan (visible every turn as formatted Markdown) "
             + "and clears all pinned files. "
             + "Write the plan using the prescribed structure: title, high-level objective, "
             + "then numbered milestones each with status, objective, relevant files, context, "
             + "and exit criteria checkboxes. "
             + "Use update_plan after starting or completing each milestone to advance its status. "
             + "After calling this, pin only the files for the current milestone before editing.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject planProp = new JsonObject();
        planProp.addProperty("type", "string");
        planProp.addProperty("description",
                "The resolution plan as structured Markdown. Use exactly this format:\n\n"
              + "# Technical Spike: [short title]\n\n"
              + "**Objective:** [One or two sentences describing the overall goal.]\n"
              + "**Progress:** 0 / N milestones complete\n\n"
              + "---\n\n"
              + "## Milestone 1: [title]\n\n"
              + "**Status:** NOT STARTED\n"
              + "**Objective:** [specific goal for this milestone]\n\n"
              + "**Relevant files:**\n"
              + "- `src/path/To.java`\n\n"
              + "**Context:** [brief context or constraints for this milestone]\n\n"
              + "**Exit criteria:**\n"
              + "- [ ] Code compiles without errors\n"
              + "- [ ] [other specific criterion]\n\n"
              + "---\n\n"
              + "## Milestone 2: [title]\n\n"
              + "[same structure — end with --- separator]\n\n"
              + "Milestone status values: NOT STARTED | IN PROGRESS | COMPLETE\n"
              + "Update statuses, tick checkboxes, and update **Progress:** count via update_plan as you work.");
        props.add("plan", planProp);

        schema.add("properties", props);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("plan");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) { return "Creating resolution plan"; }

    @Override
    public String execute(JsonObject params, Project project) {
        String plan = params.get("plan").getAsString().trim();
        if (plan.isEmpty()) return "Error: plan cannot be empty.";

        ContextManager cm = ContextManager.getInstance(project);
        cm.setCurrentPlan(plan);
        String clearResult = cm.clearAIEntries();

        return "Plan stored. " + clearResult + "\n\n"
             + "You are now in the RESOLVE phase. Work through each milestone in order:\n"
             + "  1. Call update_plan to set the milestone status to IN PROGRESS\n"
             + "  2. Pin only the files listed under that milestone (add_to_context)\n"
             + "  3. Make the required changes (replace_in_file or create_file)\n"
             + "  4. Verify exit criteria (compile_project, run tests as needed)\n"
             + "  5. Tick off exit-criteria checkboxes and set status to COMPLETE via update_plan\n"
             + "  6. Unpin milestone files, then move to the next milestone\n\n"
             + "Your plan is shown in the system context every turn — you do not need to re-read it.";
    }
}
