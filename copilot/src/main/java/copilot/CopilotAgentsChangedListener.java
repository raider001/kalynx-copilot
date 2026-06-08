package copilot;

import com.intellij.util.messages.Topic;

/**
 * Application-level message bus topic fired whenever the Copilot agent
 * list is saved.  Subscribe on the application message bus to be notified
 * without polling or manual wiring.
 *
 * <pre>
 *   ApplicationManager.getApplication().getMessageBus()
 *       .connect(disposable)
 *       .subscribe(CopilotAgentsChangedListener.TOPIC, this::onAgentsChanged);
 * </pre>
 */
public interface CopilotAgentsChangedListener {

    Topic<CopilotAgentsChangedListener> TOPIC =
            Topic.create("CopilotAgentsChanged", CopilotAgentsChangedListener.class);

    /** Called on the EDT after the agent list has been persisted. */
    void agentsChanged();
}

