package copilot.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Represents a single message in a conversation.
 * Supports all roles used by the OpenAI chat completions API,
 * including tool-call requests (ASSISTANT) and tool results (TOOL).
 */
public class ChatMessage {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    private final Role role;
    private final String content;          // null for assistant messages that only have tool_calls
    private JsonArray toolCalls;           // populated when role=ASSISTANT and model called tools
    private String toolCallId;             // populated when role=TOOL
    private String toolName;               // populated when role=TOOL

    private ChatMessage(Role role, String content) {
        this.role = role;
        this.content = content;
    }

    // --- factory helpers ---

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }

    /** Assistant message that contains only tool-call requests (no text content). */
    public static ChatMessage assistantWithToolCalls(JsonArray toolCalls) {
        ChatMessage msg = new ChatMessage(Role.ASSISTANT, null);
        msg.toolCalls = toolCalls;
        return msg;
    }

    /** Tool result message returned after executing a tool call. */
    public static ChatMessage toolResult(String toolCallId, String toolName, String content) {
        ChatMessage msg = new ChatMessage(Role.TOOL, content);
        msg.toolCallId = toolCallId;
        msg.toolName = toolName;
        return msg;
    }

    // --- accessors ---

    public Role getRole() { return role; }
    public String getContent() { return content; }
    public boolean hasToolCalls() { return toolCalls != null && toolCalls.size() > 0; }

    /** Serialise to the JSON format expected by the chat completions API. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("role", role.name().toLowerCase());

        if (content != null) {
            json.addProperty("content", content);
        }

        if (toolCalls != null) {
            json.add("tool_calls", toolCalls);
        }

        if (role == Role.TOOL) {
            json.addProperty("tool_call_id", toolCallId != null ? toolCallId : "");
        }

        return json;
    }
}

