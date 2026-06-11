package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import copilot.context.ContextManager;
import copilot.tools.api.AgentTool;
import copilot.tools.api.PathGuard;
import copilot.review.ChangeReviewManager;
import copilot.review.PendingChange;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Applies a targeted code replacement immediately to disk and registers it
 * in the Review panel so the user can accept (keep) or reject (revert) it.
 */
public class ReplaceInFileTool implements AgentTool {

    @Override public String getName() { return "replace_in_file"; }
    @Override public boolean shouldShowResultInChat() { return true; }

    @Override
    public String getDescription() {
        return "Replace a specific block of code in a file. " +
               "Provide the file path (relative to project root), the exact old_code to find, " +
               "and the new_code to replace it with. " +
               "old_code must match the file exactly, including whitespace and indentation.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject fileProp = new JsonObject();
        fileProp.addProperty("type", "string");
        fileProp.addProperty("description", "File path relative to the project root.");
        props.add("file_path", fileProp);

        JsonObject oldProp = new JsonObject();
        oldProp.addProperty("type", "string");
        oldProp.addProperty("description", "The exact text to find (must be an exact match).");
        props.add("old_code", oldProp);

        JsonObject newProp = new JsonObject();
        newProp.addProperty("type", "string");
        newProp.addProperty("description", "The replacement text.");
        props.add("new_code", newProp);

        schema.add("properties", props);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("file_path");
        required.add("old_code");
        required.add("new_code");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) {
        String path = args.has("file_path") ? args.get("file_path").getAsString() : "file";
        return "Editing: " + path;
    }

    @Override
    public String execute(JsonObject params, Project project) throws Exception {
        String relativePath = params.get("file_path").getAsString();
        String oldCode      = params.get("old_code").getAsString();
        String newCode      = params.get("new_code").getAsString();

        String basePath = project.getBasePath();
        if (basePath == null) return "Error: Cannot determine project base path.";

        String err = PathGuard.check(basePath, relativePath);
        if (err != null) return err;

        String fullPath = (basePath + "/" + relativePath).replace("\\", "/");
        final String[] result = {null};

        ApplicationManager.getApplication().invokeAndWait(() -> {
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath);
            if (vf == null || !vf.exists()) {
                result[0] = "Error: File not found: " + relativePath;
                return;
            }
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) {
                result[0] = "Error: Cannot open document for: " + relativePath;
                return;
            }

            String originalContent = doc.getText();

            int idx = originalContent.indexOf(oldCode);
            String newContent;
            if (idx >= 0) {
                newContent = originalContent.substring(0, idx) + newCode
                           + originalContent.substring(idx + oldCode.length());
            } else {
                String normOrig = originalContent.replace("\r\n", "\n");
                String normOld  = oldCode.replace("\r\n", "\n");
                int normIdx = normOrig.indexOf(normOld);
                if (normIdx < 0) {
                    // Idempotency: if new_code is already present the edit is already done.
                    if (normOrig.contains(newCode.replace("\r\n", "\n"))) {
                        result[0] = "Step complete: '" + relativePath
                                + "' already contains the intended content — "
                                + "a previous edit already applied this change. Move on to the next step.";
                        return;
                    }

                    // Pin the file so its current content appears in the ## section.
                    ContextManager.getInstance(project).addEntryForAI(relativePath);

                    // Check pinned files first — catches cross-file confusion.
                    String crossFileHint = findInPinnedFiles(project, basePath, normOld, relativePath);
                    if (crossFileHint != null) {
                        result[0] = "EDIT REJECTED: " + crossFileHint;
                        return;
                    }
                    // Project-wide scan for the anchor text.
                    String fileHint = findFilesContaining(basePath, normOld, relativePath);
                    if (!fileHint.isEmpty()) {
                        result[0] = "EDIT REJECTED: anchor not in '" + relativePath + "'. " + fileHint
                                  + "Next action: re-try with file_path set to the correct file from that list.";
                        return;
                    }
                    // Not found anywhere — anchor has drifted since the file was last read.
                    String filename = java.nio.file.Path.of(relativePath).getFileName().toString();
                    result[0] = "EDIT REJECTED: anchor not found in '" + relativePath
                              + "' — file content has changed. "
                              + "Next action: re-read the ## " + filename + " section (just re-pinned), "
                              + "copy the exact current text for old_code, and retry.";
                    return;
                }
                newContent = normOrig.substring(0, normIdx) + newCode
                           + normOrig.substring(normIdx + normOld.length());
            }

            final String finalContent = newContent;
            WriteCommandAction.runWriteCommandAction(project, () -> {
                doc.setText(finalContent);
                FileDocumentManager.getInstance().saveDocument(doc);
            });
            // Open in editor so the daemon tracks and analyses the edited file.
            FileEditorManager.getInstance(project).openFile(vf, false);

            PendingChange change = new PendingChange(relativePath, fullPath,
                    originalContent, newContent, PendingChange.ChangeType.MODIFY);
            ChangeReviewManager.getInstance(project).addChange(change);

            // Re-pin so the next system message reflects the post-edit file state.
            ContextManager.getInstance(project).addEntryForAI(relativePath);

            int oldLines = oldCode.split("\n", -1).length;
            int newLines = newCode.split("\n", -1).length;
            String delta = newLines > oldLines ? "+" + (newLines - oldLines) + " line(s)"
                         : newLines < oldLines ? "-" + (oldLines - newLines) + " line(s)"
                         : "same line count";
            result[0] = "Applied: " + relativePath + " (" + delta + ")\n\n"
                      + "--- replaced:\n" + truncate(oldCode, 30) + "\n\n"
                      + "+++ with:\n" + truncate(newCode, 30);
            ContextManager.getInstance(project).getPhaseController().notifyEdit(relativePath);
        });

        return result[0];
    }

    /** Checks currently AI-pinned files for {@code searchText}.
     * Returns a hint string if found in a different pinned file, or {@code null} if not. */
    private static String findInPinnedFiles(Project project, String basePath,
                                            String searchText, String excludeRelPath) {
        String excludeNorm = excludeRelPath.replace("\\", "/");
        List<String> pinnedPaths = ContextManager.getInstance(project).getAIPinnedPaths();
        for (String pinned : pinnedPaths) {
            String pinnedNorm = pinned.replace("\\", "/");
            if (pinnedNorm.equals(excludeNorm)) continue;
            try {
                String content = Files.readString(Path.of(basePath, pinned), StandardCharsets.UTF_8)
                                      .replace("\r\n", "\n");
                if (content.contains(searchText)) {
                    return "anchor found in pinned file '" + pinned + "' — wrong file_path was used. "
                         + "Next action: use file_path=\"" + pinned + "\" and retry with the same old_code.\n";
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    /** Scans the whole project for source files containing the most distinctive line of {@code searchText}. */
    private static String findFilesContaining(String basePath, String searchText, String excludeRelPath) {
        String key = Arrays.stream(searchText.split("\n"))
                .map(String::trim)
                .filter(l -> l.length() >= 10)
                .max(Comparator.comparingInt(String::length))
                .orElse("");
        if (key.isEmpty()) return "";

        List<String> found = new ArrayList<>();
        Path root = Path.of(basePath);
        String excludeNorm = excludeRelPath.replace("\\", "/");

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private static final List<String> SKIP =
                        List.of(".git", ".idea", "build", "out", "target", ".gradle", "node_modules");

                @Override
                public @NotNull FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes a) {
                    return SKIP.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes a) {
                    if (found.size() >= 5) return FileVisitResult.TERMINATE;
                    String ext = file.toString();
                    if (!ext.endsWith(".java") && !ext.endsWith(".kt") &&
                        !ext.endsWith(".xml")  && !ext.endsWith(".kts")) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = root.relativize(file).toString().replace("\\", "/");
                    if (rel.equals(excludeNorm)) return FileVisitResult.CONTINUE;
                    try {
                        String content = Files.readString(file, StandardCharsets.UTF_8);
                        if (content.contains(key)) found.add(rel);
                    } catch (IOException ignored) {}
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}

        if (found.isEmpty()) return "";
        return "anchor found in project files: " + found + "\n";
    }

    private static String truncate(String text, int maxLines) {
        String[] lines = text.split("\n", -1);
        if (lines.length <= maxLines) return text;
        String head = String.join("\n", Arrays.copyOfRange(lines, 0, maxLines));
        return head + "\n... (" + (lines.length - maxLines) + " more lines)";
    }
}
