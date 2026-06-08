package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.context.ContextManager;
import copilot.tools.api.AgentTool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pins a file to the dynamic context so its current content appears in the
 * system message for every subsequent turn.
 */
public class ReadFileTool implements AgentTool {

    @Override public String getName() { return "read_file"; }

    @Override
    public String getDescription() {
        return "Pin a file to your dynamic context so its current content is visible in " +
               "the system message. Always call this before replace_in_file — construct " +
               "old_code verbatim from the ## section in the context block, not from memory. " +
               "Path must be relative to the project root.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject pathProp = new JsonObject();
        pathProp.addProperty("type", "string");
        pathProp.addProperty("description", "File path relative to the project root.");
        props.add("path", pathProp);
        schema.add("properties", props);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("path");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) {
        String path = args.has("path") ? args.get("path").getAsString() : "file";
        return "Reading: " + path;
    }

    @Override
    public String execute(JsonObject params, Project project) {
        String path = params.has("path") ? params.get("path").getAsString().trim() : "";
        if (path.isEmpty()) return "Error: path is required";

        // Pin the file so content appears in every subsequent system message
        String pinResult = ContextManager.getInstance(project).addEntryForAI(path);
        if (pinResult.startsWith("Error:")) return pinResult;

        // Return content immediately so the model can act without waiting for the next turn.
        // Cap at 30 KB inline — the full file is always available via the pinned context block.
        String basePath = project.getBasePath();
        if (basePath == null) return pinResult;
        try {
            String content = Files.readString(Path.of(basePath, path), StandardCharsets.UTF_8);
            String ext = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : "";
            final int MAX_INLINE = 30_000;
            String inline = content.length() > MAX_INLINE
                    ? content.substring(0, MAX_INLINE) + "\n... [truncated — full content is in the pinned context]"
                    : content;
            return pinResult + "\n\n```" + ext + "\n" + inline + "\n```";
        } catch (Exception e) {
            return pinResult; // fall back to pin-only if read fails
        }
    }
}
