package copilot.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import copilot.context.ContextManager;
import copilot.tools.api.AgentTool;

/**
 * Returns the currently open file by pinning it to dynamic context (so the AI
 * has it in future turns too) and reporting any active selection.
 */
public class GetCurrentFileTool implements AgentTool {

    @Override
    public String getName() { return "get_current_file"; }

    @Override
    public String getDescription() {
        return "Find out which file the user currently has open in the editor. "
             + "The file is automatically pinned to your dynamic context so you "
             + "have its content now and in all future turns. "
             + "Also reports any text the user has selected. "
             + "Call this once at the start of a session to orient yourself.";
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override
    public String getStatusMessage(JsonObject args) {
        return "Getting current file";
    }

    @Override
    public String execute(JsonObject params, Project project) throws Exception {
        final Editor[] editorHolder = {null};
        ApplicationManager.getApplication().invokeAndWait(() ->
                editorHolder[0] = FileEditorManager.getInstance(project).getSelectedTextEditor()
        );

        Editor editor = editorHolder[0];
        if (editor == null) {
            return "No file is currently open in the editor. Use list_files to navigate the project.";
        }

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (vf == null) {
                return "Could not determine the current file. Use list_files to navigate.";
            }

            // Derive relative path and auto-pin — content comes back in the pin result.
            String rel = ContextManager.getInstance(project).relativizePath(project, vf.getPath());
            String pinResult = ContextManager.getInstance(project).addEntryForAI(rel);

            StringBuilder sb = new StringBuilder(pinResult);

            SelectionModel sel = editor.getSelectionModel();
            if (sel.hasSelection()) {
                sb.append("\n\n--- USER SELECTION ---\n").append(sel.getSelectedText());
            }

            return sb.toString();
        });
    }
}
