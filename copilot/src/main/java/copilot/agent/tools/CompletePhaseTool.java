package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.agent.Phase;
import copilot.agent.PhaseController;
import copilot.context.ContextManager;
import copilot.tools.api.AgentTool;

/**
 * Signals completion of the current workflow phase and requests a transition to the next.
 * The plugin validates preconditions server-side; only VERIFY → DONE is a hard gate.
 */
public class CompletePhaseTool implements AgentTool {

    @Override public String getName()                 { return "complete_phase"; }
    @Override public boolean shouldShowResultInChat() { return false; }

    @Override
    public String getDescription() {
        return "Signal that you are done with the current phase and want to move to the next. "
             + "Provide current_phase (the phase you are in) and optionally next_phase. "
             + "Default transitions: ANALYSE→PLAN, PLAN→IMPLEMENT, IMPLEMENT→VERIFY, VERIFY→DONE. "
             + "From VERIFY you may also specify next_phase=IMPLEMENT (more milestones) "
             + "or next_phase=ANALYSE (re-diagnose). "
             + "The VERIFY→DONE gate is hard: a passing compile/test must have run after the last edit. "
             + "If this task has no testable output (docs-only, config change, etc.), "
             + "supply waive_reason to skip the gate.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject curr = new JsonObject();
        curr.addProperty("type", "string");
        curr.addProperty("description", "The phase you are leaving: ANALYSE, PLAN, IMPLEMENT, or VERIFY.");
        props.add("current_phase", curr);

        JsonObject next = new JsonObject();
        next.addProperty("type", "string");
        next.addProperty("description", "Desired next phase (optional — defaults to the natural successor).");
        props.add("next_phase", next);

        JsonObject waive = new JsonObject();
        waive.addProperty("type", "string");
        waive.addProperty("description",
                "Optional. Reason to waive the VERIFY→DONE gate, e.g. 'docs-only change, no tests applicable'.");
        props.add("waive_reason", waive);

        schema.add("properties", props);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("current_phase");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) {
        String from = args.has("current_phase") ? args.get("current_phase").getAsString() : "?";
        String to   = args.has("next_phase")    ? args.get("next_phase").getAsString()    : "next";
        return "Phase: " + from + " → " + to;
    }

    @Override
    public String execute(JsonObject params, Project project) {
        String fromStr   = params.has("current_phase") ? params.get("current_phase").getAsString().trim().toUpperCase() : "";
        String nextStr   = params.has("next_phase") && !params.get("next_phase").isJsonNull()
                         ? params.get("next_phase").getAsString().trim().toUpperCase() : null;
        String waive     = params.has("waive_reason") && !params.get("waive_reason").isJsonNull()
                         ? params.get("waive_reason").getAsString().trim() : null;

        Phase from;
        try { from = Phase.valueOf(fromStr); }
        catch (IllegalArgumentException e) {
            return "Error: unknown current_phase '" + fromStr + "'. Valid: ANALYSE, PLAN, IMPLEMENT, VERIFY.";
        }

        Phase to = defaultNext(from);
        if (nextStr != null && !nextStr.isBlank()) {
            try { to = Phase.valueOf(nextStr); }
            catch (IllegalArgumentException e) {
                return "Error: unknown next_phase '" + nextStr + "'. Valid: PLAN, IMPLEMENT, VERIFY, DONE, ANALYSE.";
            }
        }

        PhaseController pc = ContextManager.getInstance(project).getPhaseController();
        PhaseController.TransitionResult result = pc.transition(from, to, waive);

        if (!result.accepted()) return result.message();

        String msg = "Transitioned: " + from.name() + " → " + to.name()
                   + ". The system message now reflects " + to.name() + " phase instructions.";
        if (result.message() != null && !result.message().isBlank())
            msg += "\n\n" + result.message();
        return msg;
    }

    private static Phase defaultNext(Phase from) {
        return switch (from) {
            case ANALYSE  -> Phase.PLAN;
            case PLAN     -> Phase.IMPLEMENT;
            case IMPLEMENT -> Phase.VERIFY;
            case VERIFY   -> Phase.DONE;
            case DONE     -> Phase.ANALYSE;
        };
    }
}
