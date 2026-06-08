package copilot.gradle;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.tools.api.AgentTool;
import copilot.tools.api.ProcessRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Runs Gradle tasks in the currently open project and returns a compact, parsed
 * summary of failures, errors, and test results so the agent can act on them.
 *
 * <p>Automatically detects {@code gradlew} / {@code gradlew.bat} wrappers in the
 * project root, falling back to {@code gradle} on the system PATH.
 */
public class GradleBuildTool implements AgentTool {

    private static final int TIMEOUT_SECONDS = 300; // 5 minutes

    @Override
    public String getName() { return "gradle_build"; }

    @Override
    public String getDescription() {
        return "Runs one or more Gradle tasks (e.g. compileJava, test, build, publishToMavenLocal) " +
               "in the current project root. Requires a build.gradle or build.gradle.kts to be present. " +
               "Returns a compact summary of failed tasks, errors, and test results.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject tasks = new JsonObject();
        tasks.addProperty("type", "string");
        tasks.addProperty("description",
                "Space-separated Gradle tasks to run (e.g. \"build\", \"clean test\", \"compileJava\").");
        props.add("tasks", tasks);

        JsonObject args = new JsonObject();
        args.addProperty("type", "string");
        args.addProperty("description",
                "Optional extra Gradle flags (e.g. \"--info\", \"-x test\"). Default: none.");
        props.add("args", args);

        schema.add("properties", props);

        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("tasks");
        schema.add("required", required);
        return schema;
    }

    @Override public boolean shouldShowResultInChat() { return true; }

    @Override
    public String getStatusMessage(JsonObject args) {
        String tasks = args.has("tasks") ? args.get("tasks").getAsString() : "…";
        return "Running: gradle " + tasks;
    }

    @Override
    public String execute(JsonObject params, Project project) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: Cannot determine project base path.";

        File projectDir = new File(basePath);
        boolean hasBuild = new File(projectDir, "build.gradle").exists()
                        || new File(projectDir, "build.gradle.kts").exists();
        if (!hasBuild) {
            return "Error: No build.gradle or build.gradle.kts found in project root. " +
                   "This is not a Gradle project.";
        }

        String tasks      = params.has("tasks") ? params.get("tasks").getAsString().trim() : "build";
        String extraArgs  = params.has("args")  ? params.get("args").getAsString().trim()  : "";

        String executable = resolveGradle(projectDir);
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(Arrays.asList(tasks.split("\\s+")));
        if (!extraArgs.isBlank()) command.addAll(Arrays.asList(extraArgs.split("\\s+")));
        command.add("--console=plain"); // disable ANSI colors for clean parsing

        ProcessRunner.Result result = ProcessRunner.run(
                command.toArray(String[]::new), projectDir, TIMEOUT_SECONDS);

        if (result.timedOut()) {
            return "Error: Gradle build timed out after " + TIMEOUT_SECONDS + " seconds.";
        }

        return formatResult(tasks, result);
    }

    private static String resolveGradle(File projectDir) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String wrapper = isWindows ? "gradlew.bat" : "gradlew";
        File wrapperFile = new File(projectDir, wrapper);
        if (wrapperFile.exists()) return wrapperFile.getAbsolutePath();
        return "gradle"; // fall back to system PATH
    }

    private static String formatResult(String tasks, ProcessRunner.Result result) {
        String output = result.combined();
        String[] lines = output.split("\n");

        List<String> failedTasks = new ArrayList<>();
        List<String> errors      = new ArrayList<>();
        String testSummary       = null;
        String buildStatus       = null;

        Pattern failedTask = Pattern.compile("> Task :.+ FAILED");
        Pattern testResult = Pattern.compile("(\\d+ tests?,.*|.*tests? completed.*)");
        // Matches javac-style errors: "path/to/File.java:42: error: ..." and plain "error: ..."
        // Also matches Kotlin compiler ("e: file.kt:42: error: ...") and generic "ERROR" lines.
        Pattern errorLine  = Pattern.compile(
                "(.*\\.java:\\d+: error:|.*\\.kt:\\d+: error:|^error:|^\\s*e:\\s|\\bERROR\\b)",
                Pattern.CASE_INSENSITIVE);

        for (String line : lines) {
            String trimmed = line.trim();
            if (failedTask.matcher(trimmed).find())  failedTasks.add(trimmed);
            if (errorLine.matcher(trimmed).find())   errors.add(line); // keep original indentation for context
            if (testResult.matcher(trimmed).find())  testSummary = trimmed;
            if (trimmed.contains("BUILD SUCCESSFUL")) buildStatus = "SUCCESSFUL";
            if (trimmed.contains("BUILD FAILED"))     buildStatus = "FAILED";
        }

        boolean failed = "FAILED".equals(buildStatus) || result.exitCode() != 0;

        StringBuilder sb = new StringBuilder();
        sb.append("TASKS: gradle ").append(tasks).append('\n');
        sb.append("BUILD: ").append(buildStatus != null ? buildStatus : (result.exitCode() == 0 ? "SUCCESSFUL" : "FAILED")).append('\n');

        if (!failedTasks.isEmpty()) {
            sb.append("FAILED TASKS:\n");
            failedTasks.forEach(t -> sb.append("  ").append(t).append('\n'));
        }
        if (!errors.isEmpty()) {
            sb.append("ERRORS:\n");
            int shown = Math.min(errors.size(), 30);
            errors.subList(0, shown).forEach(e -> sb.append(e).append('\n'));
            if (errors.size() > 30) sb.append("  ... and ").append(errors.size() - 30).append(" more\n");
        }
        if (testSummary != null) {
            sb.append("TESTS: ").append(testSummary).append('\n');
        }

        // For failed builds, always include a tail of the raw output so the agent has full context
        // even when the error patterns above didn't capture everything.
        if (failed || lines.length <= 60) {
            int tailStart = Math.max(0, lines.length - 120);
            if (tailStart > 0) {
                sb.append("\nOUTPUT (last ").append(lines.length - tailStart).append(" lines):\n");
            } else {
                sb.append("\nFULL OUTPUT:\n");
            }
            for (int i = tailStart; i < lines.length; i++) sb.append(lines[i]).append('\n');
        }

        return sb.toString().stripTrailing();
    }
}
