package copilot.maven;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.tools.api.AgentTool;
import copilot.tools.api.ProcessRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs Maven goals in the currently open project and returns a compact, parsed
 * summary of errors, warnings, and test results so the agent can act on them.
 *
 * <p>Automatically detects {@code mvnw} / {@code mvnw.cmd} wrappers in the project
 * root, falling back to {@code mvn} on the system PATH.
 */
public class MavenBuildTool implements AgentTool {

    private static final int TIMEOUT_SECONDS = 300; // 5 minutes

    @Override
    public String getName() { return "maven_build"; }

    @Override
    public String getDescription() {
        return "Runs one or more Maven goals (e.g. compile, test, package, install, clean) " +
               "in the current project root. Requires a pom.xml to be present. " +
               "Returns a compact summary of errors, warnings, and test results.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject goals = new JsonObject();
        goals.addProperty("type", "string");
        goals.addProperty("description",
                "Space-separated Maven goals to run (e.g. \"compile\", \"clean package\", \"test\").");
        props.add("goals", goals);

        JsonObject skipTests = new JsonObject();
        skipTests.addProperty("type", "boolean");
        skipTests.addProperty("description", "Skip test execution (passes -DskipTests). Default false.");
        props.add("skip_tests", skipTests);

        schema.add("properties", props);

        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("goals");
        schema.add("required", required);
        return schema;
    }

    @Override public boolean shouldShowResultInChat() { return true; }

    @Override
    public String getStatusMessage(JsonObject args) {
        String goals = args.has("goals") ? args.get("goals").getAsString() : "…";
        return "Running: mvn " + goals;
    }

    @Override
    public String execute(JsonObject params, Project project) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: Cannot determine project base path.";

        File projectDir = new File(basePath);
        if (!new File(projectDir, "pom.xml").exists()) {
            return "Error: No pom.xml found in project root. This is not a Maven project.";
        }

        String goals = params.has("goals") ? params.get("goals").getAsString().trim() : "compile";
        boolean skipTests = params.has("skip_tests") && params.get("skip_tests").getAsBoolean();

        String executable = resolveMvn(projectDir);
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(Arrays.asList(goals.split("\\s+")));
        if (skipTests) command.add("-DskipTests");
        command.add("--batch-mode"); // non-interactive, consistent output

        ProcessRunner.Result result = ProcessRunner.run(
                command.toArray(String[]::new), projectDir, TIMEOUT_SECONDS);

        if (result.timedOut()) {
            return "Error: Maven build timed out after " + TIMEOUT_SECONDS + " seconds.";
        }

        return formatResult(goals, result);
    }

    private static String resolveMvn(File projectDir) {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");

        // Prefer the project wrapper — .exists() only, canExecute() is unreliable for .cmd on Windows
        File wrapper = new File(projectDir, win ? "mvnw.cmd" : "mvnw");
        if (wrapper.exists()) return wrapper.getAbsolutePath();

        // Try MAVEN_HOME / M2_HOME environment variables
        for (String envVar : new String[]{"MAVEN_HOME", "M2_HOME"}) {
            String home = System.getenv(envVar);
            if (home != null && !home.isBlank()) {
                File candidate = new File(home, win ? "bin/mvn.cmd" : "bin/mvn");
                if (candidate.exists()) return candidate.getAbsolutePath();
            }
        }

        // ProcessBuilder on Windows can't resolve bare "mvn" to mvn.cmd — must be explicit
        return win ? "mvn.cmd" : "mvn";
    }

    private static String formatResult(String goals, ProcessRunner.Result result) {
        String output = result.combined();
        String[] lines = output.split("\n");

        List<String> errors   = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String testSummary    = null;
        String buildStatus    = null;

        Pattern testLine = Pattern.compile("Tests run:\\s*\\d+.*");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[ERROR]"))   errors.add(trimmed);
            if (trimmed.startsWith("[WARNING]")) warnings.add(trimmed);
            if (testLine.matcher(trimmed).find()) testSummary = trimmed;
            if (trimmed.contains("BUILD SUCCESS")) buildStatus = "SUCCESS";
            if (trimmed.contains("BUILD FAILURE")) buildStatus = "FAILURE";
        }

        boolean failed = "FAILURE".equals(buildStatus) || result.exitCode() != 0;

        StringBuilder sb = new StringBuilder();
        sb.append("GOALS: mvn ").append(goals).append('\n');
        sb.append("BUILD: ").append(buildStatus != null ? buildStatus : (result.exitCode() == 0 ? "SUCCESS" : "FAILURE")).append('\n');

        if (!errors.isEmpty()) {
            sb.append("ERRORS:\n");
            int shown = Math.min(errors.size(), 30);
            errors.subList(0, shown).forEach(e -> sb.append("  ").append(e).append('\n'));
            if (errors.size() > 30) sb.append("  ... and ").append(errors.size() - 30).append(" more\n");
        }
        if (!warnings.isEmpty() && warnings.size() <= 5) {
            sb.append("WARNINGS:\n");
            warnings.forEach(w -> sb.append("  ").append(w).append('\n'));
        } else if (warnings.size() > 5) {
            sb.append("WARNINGS: ").append(warnings.size()).append(" (suppressed)\n");
        }
        if (testSummary != null) {
            sb.append("TESTS: ").append(testSummary).append('\n');
        }

        // Always include a tail of raw output on failure so the agent has full context
        // even for errors the structured parser above didn't capture.
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
