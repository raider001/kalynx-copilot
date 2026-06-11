package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessage;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import copilot.tools.api.AgentTool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Compiles the project via IntelliJ's built-in compiler.
 * Unlike gradle_build / maven_build, this requires no external toolchain on PATH
 * and returns structured errors with exact file paths and line numbers.
 */
public class CompileProjectTool implements AgentTool {

    @Override public String getName() { return "compile_project"; }

    @Override
    public String getDescription() {
        return "Compiles the project using IntelliJ's built-in compiler. " +
               "Returns all errors and warnings with file paths and line numbers. " +
               "Prefer this over gradle_build / maven_build when verifying that code " +
               "changes compile — it is faster, always available, and never fails due " +
               "to missing Gradle/Maven wrappers or PATH issues.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override public boolean shouldShowResultInChat() { return true; }

    @Override
    public String getStatusMessage(JsonObject args) {
        return "Compiling project";
    }

    @Override
    public String execute(JsonObject params, Project project) throws Exception {
        CompilerManager cm = CompilerManager.getInstance(project);
        var scope = cm.createProjectCompileScope(project);

        List<String> errors   = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean[] aborted = {false};
        CountDownLatch latch = new CountDownLatch(1);

        // CompilerManager.make() must be invoked on the EDT; the finished() callback
        // is also called on the EDT, so we use a latch to wait on this background thread.
        ApplicationManager.getApplication().invokeLater(() ->
            cm.make(scope, (abort, errorCount, warningCount, ctx) -> {
                aborted[0] = abort;
                for (CompilerMessage m : ctx.getMessages(CompilerMessageCategory.ERROR)) {
                    errors.add(formatMessage(m));
                }
                for (CompilerMessage m : ctx.getMessages(CompilerMessageCategory.WARNING)) {
                    warnings.add(formatMessage(m));
                }
                latch.countDown();
            })
        );

        if (!latch.await(120, TimeUnit.SECONDS)) {
            return "Error: Compilation timed out after 120 seconds.";
        }
        if (aborted[0]) {
            return "Error: Compilation was aborted.";
        }

        String result;
        if (errors.isEmpty()) {
            result = "BUILD SUCCESSFUL"
                   + (warnings.isEmpty() ? "" : "\n" + warnings.size() + " warning(s) — run again with details if needed.");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("BUILD FAILED\n\nERRORS (").append(errors.size()).append("):\n");
            errors.forEach(e -> sb.append("  ").append(e).append('\n'));
            if (!warnings.isEmpty()) sb.append("\nWARNINGS: ").append(warnings.size()).append(" (suppressed)");
            result = sb.toString().stripTrailing();
        }
        copilot.context.ContextManager.getInstance(project).getPhaseController()
                .notifyVerifyResult(errors.isEmpty(), errors.size(), result);
        return result;
    }

    private static String formatMessage(CompilerMessage m) {
        String text = m.getMessage();
        VirtualFile vf = m.getVirtualFile();
        if (vf == null) return text;

        Navigatable nav = m.getNavigatable();
        if (nav instanceof OpenFileDescriptor ofd) {
            // getLine() / getColumn() are 0-indexed
            int line = ofd.getLine() + 1;
            int col  = ofd.getColumn() + 1;
            return vf.getPresentableUrl() + ":" + line + ":" + col + " — " + text;
        }
        return vf.getPresentableUrl() + " — " + text;
    }
}
