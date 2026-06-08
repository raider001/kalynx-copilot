package copilot.chat;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import copilot.CopilotSettings;
import copilot.review.ReviewPanel;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the Kalynx Copilot tool window tabs:
 * Chat | [Dynamic Context] | Agentic Workflow | Guidelines | Agent Plan | Review
 */
public class CopilotChatToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentFactory cf = ContentFactory.getInstance();

        CopilotChatPanel chatPanel = new CopilotChatPanel(project);

        Content dynCtxContent = cf.createContent(
                SectionEditorPanel.dynamicContext(chatPanel), "Dynamic Context", false);

        toolWindow.getContentManager().addContent(
                cf.createContent(chatPanel, "Chat", false));

        if (CopilotSettings.getInstance().showDynamicContextTab) {
            toolWindow.getContentManager().addContent(dynCtxContent);
        }

        toolWindow.getContentManager().addContent(
                cf.createContent(SectionEditorPanel.agenticWorkflow(chatPanel), "Agentic Workflow", false));
        toolWindow.getContentManager().addContent(
                cf.createContent(SectionEditorPanel.guidelines(chatPanel), "Guidelines", false));
        toolWindow.getContentManager().addContent(
                cf.createContent(new AgentPlanPanel(project, chatPanel), "Agent Plan", false));
        toolWindow.getContentManager().addContent(
                cf.createContent(new ReviewPanel(project), "Review", false));

        chatPanel.addSettingsChangeListener(() -> {
            ContentManager cm = toolWindow.getContentManager();
            boolean shouldShow = CopilotSettings.getInstance().showDynamicContextTab;
            boolean shown = isContentPresent(cm, dynCtxContent);
            if (shouldShow && !shown) {
                cm.addContent(dynCtxContent, 1);
            } else if (!shouldShow && shown) {
                cm.removeContent(dynCtxContent, false);
            }
        });
    }

    private static boolean isContentPresent(ContentManager cm, Content target) {
        for (Content c : cm.getContents()) {
            if (c == target) return true;
        }
        return false;
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }
}
