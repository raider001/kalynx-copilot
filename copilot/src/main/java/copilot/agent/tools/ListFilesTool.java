package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.tools.api.AgentTool;
import copilot.tools.api.PathGuard;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lists files inside a project directory up to a limited depth.
 * Skips common build/VCS artefacts (.git, target, build, node_modules).
 */
public class ListFilesTool implements AgentTool {

    private static final List<String> SKIP_DIRS =
            Arrays.asList(".git", ".idea", "target", "build", "out", "node_modules", ".gradle");

    @Override
    public String getName() { return "list_files"; }

    @Override
    public String getDescription() {
        return "List files in a project directory. " +
               "Provide an optional path relative to the project root; defaults to the project root.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();
        JsonObject dirProp = new JsonObject();
        dirProp.addProperty("type", "string");
        dirProp.addProperty("description",
                "Directory path relative to project root. Defaults to project root if omitted.");
        props.add("directory", dirProp);
        schema.add("properties", props);

        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) {
        String dir = args.has("directory") && !args.get("directory").getAsString().isBlank()
                ? args.get("directory").getAsString() : "project root";
        return "Listing files in: " + dir;
    }

    public String execute(JsonObject params, Project project) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: Cannot determine project base path.";

        String relativePath = (params.has("directory") && !params.get("directory").getAsString().isBlank())
                ? params.get("directory").getAsString() : ".";

        String err = PathGuard.check(basePath, relativePath);
        if (err != null) return err;

        File root = new File(basePath, relativePath);
        if (!root.exists() || !root.isDirectory()) {
            return "Error: Directory not found: " + relativePath;
        }

        List<String> files = new ArrayList<>();
        collectFiles(root, basePath, files, 0, 4);

        if (files.isEmpty()) return "Directory is empty: " + relativePath;

        // Enhanced output with directory info
        StringBuilder result = new StringBuilder();
        result.append("Ã°Å¸â€œÂ Listing files in: ").append(relativePath).append("\n\n");
        result.append(String.join("\n", files));
        return result.toString();
    }

    private void collectFiles(File dir, String basePath, List<String> result, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        File[] children = dir.listFiles();
        if (children == null) return;

        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (File child : children) {
            if (SKIP_DIRS.contains(child.getName())) continue;
            String rel = child.getAbsolutePath().replace(basePath, "").replace("\\", "/");
            if (rel.startsWith("/")) rel = rel.substring(1);
            if (child.isDirectory()) {
                result.add(rel + "/");
                collectFiles(child, basePath, result, depth + 1, maxDepth);
            } else {
                result.add(rel);
            }
            if (result.size() > 500) return; // safety limit
        }
    }
}


