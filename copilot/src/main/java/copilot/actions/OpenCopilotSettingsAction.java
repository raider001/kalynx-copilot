package copilot.actions;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import copilot.CopilotSettingsForm;
import copilot.CopilotUtil;
import org.jetbrains.annotations.NotNull;

public class OpenCopilotSettingsAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        CopilotSettingsForm settingsForm = new CopilotSettingsForm();
        if (settingsForm.showAndGet()) {
            // applyToSettings() already published CopilotAgentsChangedListener.TOPIC
            // so CopilotChatPanel refreshes automatically — no manual wiring needed.
            CopilotUtil.showNotification(e.getProject(),
                "Kalynx Copilot",
                "Settings saved successfully!",
                NotificationType.INFORMATION);
        }
    }
}