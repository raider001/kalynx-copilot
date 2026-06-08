package copilot.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import copilot.chat.CopilotChatPanel;
import org.jetbrains.annotations.NotNull;

/**
 * Right-click (or keyboard shortcut) action that opens the Copilot chat window
 * and pre-fills it with the selected text and a prompt.
 *
 * <p>If text is selected: "Explain this code:\n```\n{selection}\n```"
 * <p>If nothing is selected: just opens the chat window.
 */
public class AskCopilotAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        // Open / focus the Kalynx Copilot tool window
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Kalynx Copilot");
        if (toolWindow == null) return;
        toolWindow.show();

        // Pre-fill the input with the selected code (if any)
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor != null) {
            SelectionModel sel = editor.getSelectionModel();
            if (sel.hasSelection()) {
                String selectedText = sel.getSelectedText();
                if (selectedText != null && !selectedText.isBlank()) {
                    String prompt = "Explain this code:\n```\n" + selectedText + "\n```";
                    // Find the chat panel from the tool window content
                    toolWindow.getContentManager().getContents();
                    if (toolWindow.getContentManager().getContentCount() > 0) {
                        var comp = toolWindow.getContentManager().getContent(0);
                        if (comp != null && comp.getComponent() instanceof CopilotChatPanel panel) {
                            panel.setInputText(prompt);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}

