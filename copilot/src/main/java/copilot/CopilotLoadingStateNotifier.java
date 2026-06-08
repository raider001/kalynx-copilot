package copilot;

import com.intellij.util.messages.Topic;

/**
 * Application-level message bus topic for broadcasting the Copilot loading state.
 *
 * <p>Publish on the application bus to lock all {@link copilot.ui.LoadingButton}
 * instances across the UI simultaneously:
 * <pre>
 *   ApplicationManager.getApplication().getMessageBus()
 *       .syncPublisher(CopilotLoadingStateNotifier.TOPIC)
 *       .loadingStarted();
 * </pre>
 */
public interface CopilotLoadingStateNotifier {

    Topic<CopilotLoadingStateNotifier> TOPIC =
            Topic.create("CopilotLoadingState", CopilotLoadingStateNotifier.class);

    /** Called when a background operation begins (e.g. Test Connection). */
    void loadingStarted();

    /** Called when the background operation finishes (success or failure). */
    void loadingStopped();
}

