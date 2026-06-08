package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.tools.api.AgentTool;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Searches for a text pattern across all source files in the project
 * and returns the matching lines with file paths and line numbers.
 */
public class SearchInFilesTool implements AgentTool {

    private static final List<String> SKIP_DIRS =
            Arrays.asList(".git", ".idea", "target", "build", "out", "node_modules", ".gradle");

    @Override
    public String getName() { return "search_in_files"; }

    @Override
    public String getDescription() {
        return "Search for a text pattern across all source files in the project. " +
               "Returns matching lines with file path and line number. " +
               "The query is treated as a case-insensitive substring search by default, " +
               "or as a regex when use_regex is true.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject queryProp = new JsonObject();
        queryProp.addProperty("type", "string");
        queryProp.addProperty("description", "Text or regex pattern to search for.");
        props.add("query", queryProp);

        JsonObject regexProp = new JsonObject();
        regexProp.addProperty("type", "boolean");
        regexProp.addProperty("description", "Treat query as a regex. Defaults to false.");
        props.add("use_regex", regexProp);

        JsonObject extProp = new JsonObject();
        extProp.addProperty("type", "string");
        extProp.addProperty("description",
                "Only search files with this extension, e.g. \"java\". Omit to search all text files.");
        props.add("file_extension", extProp);

        schema.add("properties", props);

        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("query");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) {
        String query = args.has("query") ? args.get("query").getAsString() : "â€¦";
        String ext   = args.has("file_extension") ? " (*." + args.get("file_extension").getAsString() + ")" : "";
        return "Searching for \"" + query + "\"" + ext;
    }

    public String execute(JsonObject params, Project project) throws Exception {
        String query = params.get("query").getAsString();
        boolean useRegex = params.has("use_regex") && params.get("use_regex").getAsBoolean();
        String extension = params.has("file_extension")
                ? params.get("file_extension").getAsString().toLowerCase().trim() : null;

        String basePath = project.getBasePath();
        if (basePath == null) return "Error: Cannot determine project base path.";

        Pattern pattern;
        if (useRegex) {
            pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
        } else {
            pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        }

        List<String> results = new ArrayList<>();
        Path root = Paths.get(basePath);

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (SKIP_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (results.size() >= 100) return FileVisitResult.CONTINUE;

                String fileName = file.getFileName().toString();
                if (extension != null && !fileName.toLowerCase().endsWith("." + extension)) {
                    return FileVisitResult.CONTINUE;
                }
                // Skip binary files
                if (fileName.endsWith(".class") || fileName.endsWith(".jar") ||
                        fileName.endsWith(".png") || fileName.endsWith(".jpg")) {
                    return FileVisitResult.CONTINUE;
                }

                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (pattern.matcher(lines.get(i)).find()) {
                            String rel = root.relativize(file).toString().replace("\\", "/");
                            results.add(rel + ":" + (i + 1) + ": " + lines.get(i).trim());
                        }
                        if (results.size() >= 100) break;
                    }
                } catch (Exception ignored) { /* skip unreadable files */ }

                return FileVisitResult.CONTINUE;
            }
        });

        if (results.isEmpty()) {
            StringBuilder resultBuilder = new StringBuilder();
            resultBuilder.append("Ã°Å¸â€Â No matches found for: ").append(query).append("\n");
            resultBuilder.append("Ã°Å¸â€œÂ Search parameters:\n");
            resultBuilder.append("   - Pattern: ").append(useRegex ? "(regex) " : "").append(query).append("\n");
            resultBuilder.append("   - Extension: ").append(extension != null ? extension : "all files").append("\n");
            return resultBuilder.toString();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Ã°Å¸â€Â Found ").append(results.size()).append(" match(es) for: ").append(query).append("\n\n");
        if (useRegex) {
            sb.append("Ã°Å¸â€œÂ Search type: regex pattern\n");
        } else {
            sb.append("Ã°Å¸â€œÂ Search type: case-insensitive substring\n");
        }
        if (extension != null) {
            sb.append("Ã°Å¸â€œÂ File extension: *.").append(extension).append("\n");
        }
        sb.append("\n");
        results.forEach(r -> sb.append(r).append("\n"));
        return sb.toString();
    }
}


