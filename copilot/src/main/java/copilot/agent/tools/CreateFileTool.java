package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import copilot.tools.api.AgentTool;
import copilot.tools.api.PathGuard;
import copilot.review.ChangeReviewManager;
import copilot.review.PendingChange;

import java.io.File;

/**
 * Creates a new file immediately on disk and registers it in the Review panel
 * so the user can accept (keep) or reject (delete) it.
 */
public class CreateFileTool implements AgentTool {

    @Override public String getName() { return "create_file"; }

    @Override
    public String getDescription() {
        return "Create a new file in the project with the given content. " +
               "Parent directories will be created automatically. " +
               "Provide the path relative to the project root and the full file content.";
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

        JsonObject contentProp = new JsonObject();
        contentProp.addProperty("type", "string");
        contentProp.addProperty("description", "Full content to write to the file.");
        props.add("content", contentProp);

        schema.add("properties", props);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("path");
        required.add("content");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) {
        String path = args.has("path") ? args.get("path").getAsString() : "file";
        return "Creating: " + path;
    }

    @Override
    public String execute(JsonObject params, Project project) throws Exception {
        String relativePath = params.get("path").getAsString();
        String content      = params.get("content").getAsString();

        String basePath = project.getBasePath();
        if (basePath == null) return "Error: Cannot determine project base path.";

        String err = PathGuard.check(basePath, relativePath);
        if (err != null) return err;

        String fullPath = (basePath + "/" + relativePath).replace("\\", "/");
        final String[] result = {null};

        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                File targetFile = new File(basePath, relativePath);
                targetFile.getParentFile().mkdirs();

                VirtualFile parentVf = LocalFileSystem.getInstance()
                        .refreshAndFindFileByPath(targetFile.getParent().replace("\\", "/"));
                if (parentVf == null) { result[0] = "Error: Could not resolve parent directory."; return; }

                VirtualFile newVf = parentVf.findChild(targetFile.getName());
                if (newVf == null) newVf = parentVf.createChildData(this, targetFile.getName());
                VfsUtil.saveText(newVf, content);

                FileEditorManager.getInstance(project).openFile(newVf, true);

                // Register in Review panel so the user can accept (keep) or reject (delete)
                PendingChange change = new PendingChange(relativePath, fullPath,
                        null, content, PendingChange.ChangeType.CREATE);
                ChangeReviewManager.getInstance(project).addChange(change);

                result[0] = "Created and added to Review panel: " + relativePath;
                copilot.context.ContextManager.getInstance(project).getPhaseController().notifyEdit(relativePath);
            } catch (Exception e) {
                result[0] = "Error creating file: " + e.getMessage();
            }
        });

        return result[0];
    }
}
