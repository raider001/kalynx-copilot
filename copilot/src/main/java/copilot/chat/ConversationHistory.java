package copilot.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages the ordered list of messages for an ongoing conversation.
 * Keeps the system message pinned at position 0 and optionally trims
 * older messages when the history exceeds a configurable limit.
 */
public class ConversationHistory {

    private final List<ChatMessage> messages = new ArrayList<>();
    private int maxMessages = 60; // includes system message

    public void add(ChatMessage message) {
        messages.add(message);
        trim();
    }

    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * Strip tool-call and tool-result messages from history, keeping only text turns.
     */
    public void pruneToolMessages() {
        messages.removeIf(m -> m.hasToolCalls() || m.getRole() == ChatMessage.Role.TOOL);
    }

    /** Remove all messages except the system prompt (if present). */
    public void clear() {
        ChatMessage systemMsg = null;
        if (!messages.isEmpty() && messages.get(0).getRole() == ChatMessage.Role.SYSTEM) {
            systemMsg = messages.get(0);
        }
        messages.clear();
        if (systemMsg != null) {
            messages.add(systemMsg);
        }
    }

    /** Replace (or insert at position 0) the system message. */
    public void setSystemMessage(String content) {
        ChatMessage sys = ChatMessage.system(content);
        if (!messages.isEmpty() && messages.get(0).getRole() == ChatMessage.Role.SYSTEM) {
            messages.set(0, sys);
        } else {
            messages.add(0, sys);
        }
    }

    public boolean isEmpty() {
        long nonSystem = messages.stream()
                .filter(m -> m.getRole() != ChatMessage.Role.SYSTEM)
                .count();
        return nonSystem == 0;
    }

    private void trim() {
        while (messages.size() > maxMessages) {
            int removeIdx = (messages.get(0).getRole() == ChatMessage.Role.SYSTEM) ? 1 : 0;
            if (removeIdx < messages.size()) {
                messages.remove(removeIdx);
            } else {
                break;
            }
        }
    }
}

