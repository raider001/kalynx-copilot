package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import copilot.tools.api.AgentTool;
import copilot.tools.api.ProcessRunner;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs unit tests via Maven or Gradle (auto-detected) and returns a per-test
 * pass/fail breakdown parsed from the JUnit XML reports each tool produces.
 * An optional filter narrows execution to a specific class or method.
 */
public class RunTestsTool implements AgentTool {

    private static final int TIMEOUT_SECONDS = 300;

    @Override public String getName()                 { return "run_tests"; }
    @Override public boolean shouldShowResultInChat() { return true; }

    @Override
    public String getDescription() {
        return "Runs unit tests using Maven or Gradle (auto-detected from the project structure). " +
               "Use the optional 'filter' to target a specific class or method: " +
               "\"MyClass\", \"MyClass#myMethod\", or \"com.example.*\". " +
               "Returns a per-test pass/fail summary with failure messages and stack traces.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject filter = new JsonObject();
        filter.addProperty("type", "string");
        filter.addProperty("description",
                "Optional test filter. Examples:\n" +
                "  \"MyTestClass\"            — all tests in that class\n" +
                "  \"MyTestClass#myMethod\"   — one specific method\n" +
                "  \"com.example.*\"          — all tests in a package\n" +
                "Omit to run all tests.");
        props.add("filter", filter);

        schema.add("properties", props);
        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) {
        if (args.has("filter") && !args.get("filter").getAsString().isBlank())
            return "Running tests: " + args.get("filter").getAsString();
        return "Running all tests";
    }

    @Override
    public String execute(JsonObject params, Project project) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: Cannot determine project base path.";
        File projectDir = new File(basePath);

        String filter = params.has("filter") ? params.get("filter").getAsString().trim() : null;
        if (filter != null && filter.isEmpty()) filter = null;

        boolean isMaven  = new File(projectDir, "pom.xml").exists();
        boolean isGradle = new File(projectDir, "build.gradle").exists()
                        || new File(projectDir, "build.gradle.kts").exists();

        if (isMaven)  return runMaven(filter, projectDir);
        if (isGradle) return runGradle(filter, projectDir);
        return "Error: No pom.xml or build.gradle / build.gradle.kts found in project root.";
    }

    // ── Maven ─────────────────────────────────────────────────────────────────

    private static String runMaven(String filter, File projectDir) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveMvn(projectDir));
        cmd.add("test");
        if (filter != null) cmd.add("-Dtest=" + filter);
        cmd.add("--batch-mode");

        ProcessRunner.Result result = ProcessRunner.run(cmd.toArray(String[]::new), projectDir, TIMEOUT_SECONDS);
        if (result.timedOut())
            return "Error: Tests timed out after " + TIMEOUT_SECONDS + " seconds.";

        return formatResults(
                "mvn test" + (filter != null ? " -Dtest=" + filter : ""),
                result,
                new File(projectDir, "target/surefire-reports"));
    }

    private static String resolveMvn(File dir) {
        // Prefer the project wrapper — .exists() only, canExecute() is unreliable for .cmd on Windows
        File wrapper = new File(dir, isWindows() ? "mvnw.cmd" : "mvnw");
        if (wrapper.exists()) return wrapper.getAbsolutePath();

        // Try MAVEN_HOME / M2_HOME environment variables
        for (String envVar : new String[]{"MAVEN_HOME", "M2_HOME"}) {
            String home = System.getenv(envVar);
            if (home != null && !home.isBlank()) {
                File candidate = new File(home, isWindows() ? "bin/mvn.cmd" : "bin/mvn");
                if (candidate.exists()) return candidate.getAbsolutePath();
            }
        }

        // ProcessBuilder on Windows can't resolve bare "mvn" to mvn.cmd — must be explicit
        return isWindows() ? "mvn.cmd" : "mvn";
    }

    // ── Gradle ────────────────────────────────────────────────────────────────

    private static String runGradle(String filter, File projectDir) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveGradle(projectDir));
        cmd.add("test");
        if (filter != null) {
            cmd.add("--tests");
            cmd.add(filter.replace('#', '.'));   // MyClass#method → MyClass.method
        }
        cmd.add("--console=plain");
        cmd.add("--continue");  // collect all failures, not just the first

        ProcessRunner.Result result = ProcessRunner.run(cmd.toArray(String[]::new), projectDir, TIMEOUT_SECONDS);
        if (result.timedOut())
            return "Error: Tests timed out after " + TIMEOUT_SECONDS + " seconds.";

        return formatResults(
                "gradle test" + (filter != null ? " --tests " + filter : ""),
                result,
                new File(projectDir, "build/test-results/test"));
    }

    private static String resolveGradle(File dir) {
        // Prefer the project wrapper — .exists() only, canExecute() is unreliable for .bat on Windows
        File wrapper = new File(dir, isWindows() ? "gradlew.bat" : "gradlew");
        if (wrapper.exists()) return wrapper.getAbsolutePath();

        // Try GRADLE_HOME environment variable
        String gradleHome = System.getenv("GRADLE_HOME");
        if (gradleHome != null && !gradleHome.isBlank()) {
            File candidate = new File(gradleHome, isWindows() ? "bin/gradle.bat" : "bin/gradle");
            if (candidate.exists()) return candidate.getAbsolutePath();
        }

        // ProcessBuilder on Windows can't resolve bare "gradle" to gradle.bat — must be explicit
        return isWindows() ? "gradle.bat" : "gradle";
    }

    // ── Result formatting ─────────────────────────────────────────────────────

    private static String formatResults(String command, ProcessRunner.Result result, File reportsDir) {
        List<TestCase> tests = parseXmlReports(reportsDir);
        if (!tests.isEmpty())
            return formatFromXml(command, tests);
        return formatFromConsole(command, result);
    }

    private static String formatFromXml(String command, List<TestCase> tests) {
        long passed  = tests.stream().filter(t -> t.status == Status.PASSED).count();
        long failed  = tests.stream().filter(t -> t.status == Status.FAILED).count();
        long errored = tests.stream().filter(t -> t.status == Status.ERROR).count();
        long skipped = tests.stream().filter(t -> t.status == Status.SKIPPED).count();

        StringBuilder sb = new StringBuilder();
        sb.append("COMMAND: ").append(command).append('\n');
        sb.append(String.format("RESULTS: %d passed, %d failed, %d error(s), %d skipped / %d total%n",
                passed, failed, errored, skipped, tests.size()));

        tests.stream()
             .filter(t -> t.status == Status.FAILED || t.status == Status.ERROR)
             .forEach(t -> {
                 sb.append('\n');
                 sb.append(t.status == Status.FAILED ? "FAIL:  " : "ERROR: ");
                 sb.append(t.className).append('#').append(t.methodName).append('\n');
                 if (t.message != null && !t.message.isBlank())
                     sb.append("  ").append(t.message.lines().findFirst().orElse("")).append('\n');
                 if (t.stackTrace != null && !t.stackTrace.isBlank()) {
                     String[] lines = t.stackTrace.strip().split("\n");
                     int show = Math.min(lines.length, 8);
                     for (int i = 0; i < show; i++)
                         sb.append("  ").append(lines[i].strip()).append('\n');
                     if (lines.length > show)
                         sb.append("  ... (").append(lines.length - show).append(" more lines)\n");
                 }
             });

        if (failed == 0 && errored == 0)
            sb.append("\nAll tests passed.");

        return sb.toString().stripTrailing();
    }

    private static String formatFromConsole(String command, ProcessRunner.Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append("COMMAND: ").append(command).append('\n');
        boolean success = result.exitCode() == 0;
        sb.append("BUILD: ").append(success ? "SUCCESSFUL" : "FAILED").append('\n');
        sb.append("\nOUTPUT:\n").append(result.combined());
        return sb.toString().stripTrailing();
    }

    // ── JUnit XML parsing ─────────────────────────────────────────────────────

    private enum Status { PASSED, FAILED, ERROR, SKIPPED }

    private static class TestCase {
        final String className;
        final String methodName;
        final Status status;
        final String message;
        final String stackTrace;

        TestCase(String className, String methodName, Status status, String message, String stackTrace) {
            this.className  = className;
            this.methodName = methodName;
            this.status     = status;
            this.message    = message;
            this.stackTrace = stackTrace;
        }
    }

    private static List<TestCase> parseXmlReports(File reportsDir) {
        List<TestCase> results = new ArrayList<>();
        if (reportsDir == null || !reportsDir.isDirectory()) return results;

        File[] xmlFiles = reportsDir.listFiles(
                f -> f.getName().startsWith("TEST-") && f.getName().endsWith(".xml"));
        if (xmlFiles == null) return results;

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        for (File xmlFile : xmlFiles) {
            try {
                Document doc = factory.newDocumentBuilder().parse(xmlFile);
                NodeList testCases = doc.getElementsByTagName("testcase");
                for (int i = 0; i < testCases.getLength(); i++) {
                    Element tc        = (Element) testCases.item(i);
                    String className  = tc.getAttribute("classname");
                    String methodName = tc.getAttribute("name");

                    Element failure = firstChild(tc, "failure");
                    Element error   = firstChild(tc, "error");
                    Element skipped = firstChild(tc, "skipped");

                    Status status;
                    String message    = null;
                    String stackTrace = null;

                    if (failure != null) {
                        status     = Status.FAILED;
                        message    = failure.getAttribute("message");
                        stackTrace = failure.getTextContent();
                    } else if (error != null) {
                        status     = Status.ERROR;
                        message    = error.getAttribute("message");
                        stackTrace = error.getTextContent();
                    } else if (skipped != null) {
                        status = Status.SKIPPED;
                    } else {
                        status = Status.PASSED;
                    }

                    results.add(new TestCase(className, methodName, status, message, stackTrace));
                }
            } catch (Exception ignored) {}
        }
        return results;
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
