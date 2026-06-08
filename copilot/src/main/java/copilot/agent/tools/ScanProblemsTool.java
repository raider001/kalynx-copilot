package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInspection.*;
import com.intellij.lang.Language;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.*;
import copilot.tools.api.AgentTool;
import copilot.tools.api.PathGuard;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs IntelliJ's local inspection tools across all project source files and
 * returns errors and warnings. No daemon or open editors required.
 */
public class ScanProblemsTool implements AgentTool {

    private static final int MAX_FILES    = 500;
    private static final int MAX_PROBLEMS = 150;

    @Override public String getName() { return "scan_problems"; }

    @Override
    public String getDescription() {
        return "Scans the project (or a subdirectory) for errors and warnings using IntelliJ's " +
               "inspection engine. Works on all source files, not just open ones. " +
               "Provide an optional 'path' to limit the scan to a specific module or package directory.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject pathProp = new JsonObject();
        pathProp.addProperty("type", "string");
        pathProp.addProperty("description",
                "Optional directory path relative to the project root to limit the scan. " +
                "Omit to scan all source files.");
        props.add("path", pathProp);

        schema.add("properties", props);
        return schema;
    }

    @Override public boolean shouldShowResultInChat() { return true; }

    @Override
    public String getStatusMessage(JsonObject args) {
        return args.has("path")
                ? "Scanning: " + args.get("path").getAsString()
                : "Scanning project for problems";
    }

    @Override
    public String execute(JsonObject params, Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            String basePath = project.getBasePath();
            if (basePath == null) return "Error: Cannot determine project base path.";

            // Optional scope restriction
            String scopePrefix = null;
            if (params.has("path") && !params.get("path").getAsString().isBlank()) {
                String rel = params.get("path").getAsString().trim();
                String guardErr = PathGuard.check(basePath, rel);
                if (guardErr != null) return guardErr;
                scopePrefix = (basePath + "/" + rel).replace("\\", "/");
            }
            final String finalScopePrefix = scopePrefix;

            // Collect source files within scope
            List<VirtualFile> sourceFiles = new ArrayList<>();
            ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
            fileIndex.iterateContent(vf -> {
                if (sourceFiles.size() >= MAX_FILES) return false;
                if (vf.isDirectory()) return true;
                if (!fileIndex.isInSourceContent(vf)) return true;
                String ext = vf.getExtension();
                if (!"java".equals(ext) && !"kt".equals(ext)) return true;
                if (finalScopePrefix != null && !vf.getPath().startsWith(finalScopePrefix)) return true;
                sourceFiles.add(vf);
                return true;
            });

            if (sourceFiles.isEmpty()) return "No source files found to scan.";

            InspectionManager im = InspectionManager.getInstance(project);
            GlobalInspectionContext globalCtx = im.createNewGlobalContext();
            InspectionProfile profile =
                    InspectionProjectProfileManager.getInstance(project).getCurrentProfile();
            PsiManager psiManager = PsiManager.getInstance(project);
            String normalBase = basePath.replace("\\", "/");

            List<String> errors   = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            outer:
            for (VirtualFile vf : sourceFiles) {
                PsiFile psiFile = psiManager.findFile(vf);
                if (psiFile == null) continue;

                String relPath = vf.getPath().replace(normalBase, "").replaceFirst("^/", "");
                Language fileLanguage = psiFile.getLanguage();

                for (InspectionToolWrapper<?, ?> wrapper : profile.getInspectionTools(psiFile)) {
                    // isKindOf() handles dialect relationships so a tool registered for "JAVA"
                    // won't run on Properties/XML files, while null-language tools run everywhere.
                    String toolLangId = wrapper.getLanguage();
                    if (toolLangId != null) {
                        Language toolLang = Language.findLanguageByID(toolLangId);
                        if (toolLang == null || !fileLanguage.isKindOf(toolLang)) continue;
                    }

                    HighlightDisplayKey key = HighlightDisplayKey.find(wrapper.getShortName());
                    if (key == null || !profile.isToolEnabled(key, psiFile)) continue;

                    HighlightSeverity severity =
                            profile.getErrorLevel(key, psiFile).getSeverity();
                    if (severity.compareTo(HighlightSeverity.WARNING) < 0) continue;

                    // runInspectionOnFile handles both LocalInspectionTool and GlobalInspectionTool
                    // (e.g. UnusedDeclarationInspection), which the old buildVisitor() approach missed.
                    List<ProblemDescriptor> problems;
                    try {
                        problems = InspectionEngine.runInspectionOnFile(psiFile, wrapper, globalCtx);
                    } catch (Exception ignored) {
                        continue;
                    }

                    for (ProblemDescriptor desc : problems) {
                        PsiElement el = desc.getPsiElement();
                        int line = el != null
                                ? psiFile.getViewProvider().getDocument()
                                         .getLineNumber(el.getTextOffset()) + 1
                                : 0;
                        String msg = relPath + (line > 0 ? ":" + line : "") +
                                     " — " + desc.getDescriptionTemplate()
                                                  .replaceAll("<[^>]+>", "");

                        if (severity.compareTo(HighlightSeverity.ERROR) >= 0) {
                            errors.add("[ERROR] " + msg);
                        } else {
                            warnings.add("[WARNING] " + msg);
                        }

                        if (errors.size() + warnings.size() >= MAX_PROBLEMS) break outer;
                    }
                }
            }

            boolean truncated = (errors.size() + warnings.size()) >= MAX_PROBLEMS
                             || sourceFiles.size() >= MAX_FILES;

            if (errors.isEmpty() && warnings.isEmpty()) {
                return "No errors or warnings found in " + sourceFiles.size() + " file(s).";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(errors.size()).append(" error(s), ")
              .append(warnings.size()).append(" warning(s)")
              .append(" across ").append(sourceFiles.size()).append(" file(s)");
            if (truncated) sb.append(" (results capped at ").append(MAX_PROBLEMS).append(")");
            sb.append(":\n\n");
            errors.forEach(e   -> sb.append(e).append('\n'));
            warnings.forEach(w -> sb.append(w).append('\n'));
            return sb.toString().stripTrailing();
        });
    }
}
