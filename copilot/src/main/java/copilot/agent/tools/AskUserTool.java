package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.tools.api.AgentTool;

/**
 * Terminates the agentic loop and poses a structured question to the user.
 * Required format: "I tried X and Y. Both failed because Z. Should I: (A) option1 or (B) option2?"
 * The plugin triggers this via the stuck-nudge when consecutiveFailedVerifies >= threshold.
 */
public class AskUserTool implements AgentTool {

    public static final String ASK_SIGNAL = "##ASK_USER## ";

    @Override public String getName() { return "ask_user"; }
    @Override public boolean shouldShowResultInChat() { return false; }

    @Override
    public String getDescription() {
        return "Stop the loop and pose a structured question to the user. " +
               "Required when stuck after multiple failed attempts (the plugin will require this). " +
               "Format: \"I tried [X] and [Y]. Both failed because [specific error]. " +
               "Should I: (A) [concrete option] or (B) [concrete option]?\" " +
               "Never use generic 'I am stuck' — always name what was tried and why it failed.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject questionProp = new JsonObject();
        questionProp.addProperty("type", "string");
        questionProp.addProperty("description",
                "Structured question: 'I tried [X] and [Y]. Both failed because [Z]. " +
                "Should I: (A) [option] or (B) [option]?'");
        props.add("question", questionProp);
        schema.add("properties", props);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("question");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) {
        if (!args.has("question")) return "Asking user...";
        String q = args.get("question").getAsString();
        return "Asking: " + q.substring(0, Math.min(60, q.length()));
    }

    @Override
    public String execute(JsonObject params, Project project) {
        String question = params.has("question") ? params.get("question").getAsString().trim() : "";
        if (question.isEmpty()) return "Error: question is required";
        return ASK_SIGNAL + question;
    }
}
