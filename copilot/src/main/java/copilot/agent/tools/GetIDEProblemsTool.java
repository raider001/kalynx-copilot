package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import copilot.tools.api.AgentTool;
import copilot.tools.api.PathGuard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads live IDE errors and warnings from the highlighting daemon.
 * Works on files currently open in the editor — the daemon only analyses open files.
 */
public class GetIDEProblemsTool implements AgentTool {

    @Override public String getName() { return "get_problems"; }

    @Override
    public String getDescription() {
        return "Returns errors and warnings currently shown by the IDE (red/yellow squiggles). " +
               "Includes compiler errors, inspection warnings, type mismatches, etc. " +
               "Only works for files open in the editor — the IDE analyses them in real time. " +
               "Omit 'path' to check all open files; provide 'path' to scope to one file.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject pathProp = new JsonObject();
        pathProp.addProperty("type", "string");
        pathProp.addProperty("description",
                "Optional file path relative to the project root. " +
                "Omit to check all currently open files.");
        props.add("path", pathProp);

        schema.add("properties", props);
        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) {
        return args.has("path")
                ? "Checking problems in: " + args.get("path").getAsString()
                : "Checking IDE problems in open files";
    }

    @Override
    public String execute(JsonObject params, Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            String basePath = project.getBasePath();
            if (basePath == null) return "Error: Cannot determine project base path.";

            List<VirtualFile> filesToCheck = new ArrayList<>();

            if (params.has("path") && !params.get("path").getAsString().isBlank()) {
                String relativePath = params.get("path").getAsString().trim();
                String guardErr = PathGuard.check(basePath, relativePath);
                if (guardErr != null) return guardErr;

                String fullPath = (basePath + "/" + relativePath).replace("\\", "/");
                VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(fullPath);
                if (vf == null || !vf.exists()) return "Error: File not found: " + relativePath;
                filesToCheck.add(vf);
            } else {
                filesToCheck.addAll(Arrays.asList(FileEditorManager.getInstance(project).getOpenFiles()));
            }

            if (filesToCheck.isEmpty()) return "No open files to check.";

            String normalBase = basePath.replace("\\", "/");
            List<String> errors   = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            for (VirtualFile vf : filesToCheck) {
                Document doc = FileDocumentManager.getInstance().getDocument(vf);
                if (doc == null) continue;

                List<HighlightInfo> highlights =
                        DaemonCodeAnalyzerImpl.getHighlights(doc, null, project);
                if (highlights == null || highlights.isEmpty()) continue;

                String relPath = vf.getPath().replace(normalBase, "").replaceFirst("^/", "");

                for (HighlightInfo info : highlights) {
                    if (info.getSeverity().compareTo(HighlightSeverity.WARNING) < 0) continue;

                    String description = info.getDescription();
                    if (description == null || description.isBlank()) continue;

                    int line = doc.getLineNumber(info.getStartOffset()) + 1;
                    int col  = info.getStartOffset() - doc.getLineStartOffset(line - 1) + 1;
                    String location = relPath + ":" + line + ":" + col;

                    if (info.getSeverity().compareTo(HighlightSeverity.ERROR) >= 0) {
                        errors.add("[ERROR] " + location + " — " + description);
                    } else {
                        warnings.add("[WARNING] " + location + " — " + description);
                    }
                }
            }

            if (errors.isEmpty() && warnings.isEmpty()) {
                int count = filesToCheck.size();
                return "No errors or warnings in " + count + " open file" + (count == 1 ? "" : "s") + ".";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(errors.size()).append(" error(s), ")
              .append(warnings.size()).append(" warning(s):\n\n");
            errors.forEach(e   -> sb.append(e).append('\n'));
            warnings.forEach(w -> sb.append(w).append('\n'));
            return sb.toString().stripTrailing();
        });
    }
}
