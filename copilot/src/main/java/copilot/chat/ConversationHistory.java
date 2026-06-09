package copilot.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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

    /**
     * Removes tool call / tool result pairs older than the {@code keepRecentPairs} most recent,
     * leaving recent tool activity intact so the model retains short-term context.
     * Non-tool messages are never touched.
     */
    public void pruneOldToolPairs(int keepRecentPairs) {
        // Group contiguous (ASSISTANT-with-tool_calls + TOOL-result) blocks into pairs by index.
        List<List<Integer>> pairs = new ArrayList<>();
        List<Integer> current = null;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (m.hasToolCalls()) {
                current = new ArrayList<>();
                current.add(i);
            } else if (m.getRole() == ChatMessage.Role.TOOL && current != null) {
                current.add(i);
            } else {
                if (current != null) { pairs.add(current); current = null; }
            }
        }
        if (current != null) pairs.add(current);

        int toRemove = pairs.size() - keepRecentPairs;
        if (toRemove <= 0) return;

        Set<Integer> removeIdx = new java.util.HashSet<>();
        for (int p = 0; p < toRemove; p++) removeIdx.addAll(pairs.get(p));

        List<ChatMessage> kept = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (!removeIdx.contains(i)) kept.add(messages.get(i));
        }
        messages.clear();
        messages.addAll(kept);
    }

    /**
     * Replaces conversation messages with the compressed set, preserving the system message.
     * The system message at index 0 is always retained regardless of what is in {@code newMessages}.
     */
    public void replaceMessages(List<ChatMessage> newMessages) {
        ChatMessage sys = (!messages.isEmpty() && messages.get(0).getRole() == ChatMessage.Role.SYSTEM)
                ? messages.get(0) : null;
        messages.clear();
        if (sys != null) messages.add(sys);
        for (ChatMessage m : newMessages) {
            // Never re-add a system message from the compressed set — we already have it
            if (m.getRole() != ChatMessage.Role.SYSTEM) messages.add(m);
        }
    }

    /** Returns the approximate number of characters across all non-system messages. */
    public int estimateContentChars() {
        return messages.stream()
                .filter(m -> m.getRole() != ChatMessage.Role.SYSTEM)
                .mapToInt(m -> m.getContent() != null ? m.getContent().length() : 0)
                .sum();
    }

    /** Returns a snapshot copy of the current message list (used for archiving before compression). */
    public List<ChatMessage> snapshot() {
        return new ArrayList<>(messages);
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

