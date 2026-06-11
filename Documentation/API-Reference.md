# Copilot-Base API Reference

## Package: copilot.core.agent

### AgentSession

```java
package copilot.core.agent;

import java.util.List;
import java.util.function.Consumer;

/**
 * The core agentic session that drives the AI coding assistant.
 * 
 * <p>This is the main entry point for using copilot-base. It manages:
 * <ul>
 *   <li>The conversation history</li>
 *   <li>Available tools</li>
 *   <li>The agent loop (calling LLM, handling tool calls)</li>
 *   <li>Context file management</li>
 * </ul>
 */
public class AgentSession {
    
    private final AgentConfig config;
    private final ConversationHistory history;
    private final List<AgentTool> tools;
    private final ContextManager contextManager;
    
    /**
     * Create a new agent session with the given configuration.
     * 
     * @param config The agent configuration
     */
    public AgentSession(AgentConfig config) {
        this.config = config;
        this.history = new ConversationHistory();
        this.tools = new ArrayList<>();
        this.contextManager = new ContextManager();
    }
    
    /**
     * Send a message and get a response.
     * 
     * <p>This is the main entry point for the agentic loop. It:
     * <ol>
     *   <li>Builds the system prompt with context files</li>
     *   <li>Sends the conversation history to the LLM</li>
     *   <li>Handles any tool calls returned by the model</li>
     *   <li>Returns the final response</li>
     * </ol>
     * 
     * @param userMessage The user's input message
     * @param callback Callback for streaming responses and status updates
     * @return The final response from the agent
     */
    public String chat(String userMessage, AgentCallback callback) {
        // Build system prompt
        String systemPrompt = buildSystemPrompt();
        history.setSystemMessage(systemPrompt);
        
        // Add user message
        history.add(ChatMessage.user(userMessage));
        
        int iterations = 0;
        while (iterations < config.getMaxIterations()) {
            // Call LLM
            LlmResponse response = callLlm(history.getMessages(), tools);
            
            // Handle tool calls
            if (response.hasToolCalls()) {
                for (ToolCall call : response.getToolCalls()) {
                    AgentTool tool = findTool(call.getName());
                    if (tool == null) {
                        history.add(ChatMessage.toolResult(
                            call.getId(), 
                            call.getName(),
                            "Error: Unknown tool"
                        ));
                    } else {
                        try {
                            String result = tool.execute(call.getArguments());
                            history.add(ChatMessage.toolResult(
                                call.getId(),
                                call.getName(),
                                result
                            ));
                        } catch (Exception e) {
                            history.add(ChatMessage.toolResult(
                                call.getId(),
                                call.getName(),
                                "Error: " + e.getMessage()
                            ));
                        }
                    }
                }
            } else {
                // No more tool calls - return the response
                String finalResponse = response.getContent();
                history.add(ChatMessage.assistant(finalResponse));
                return finalResponse;
            }
            
            iterations++;
        }
        
        return "Agent reached maximum iterations without producing a final answer.";
    }
    
    /**
     * Add a tool to be available to the agent.
     */
    public void addTool(AgentTool tool) {
        tools.add(tool);
    }
    
    /**
     * Remove a tool from availability.
     */
    public void removeTool(String toolName) {
        tools.removeIf(t -> t.getName().equals(toolName));
    }
    
    /**
     * Add a file to the dynamic context.
     * 
     * <p>The file content will be included in the system prompt
     * for subsequent requests.
     */
    public void addContextFile(String path, String content) {
        contextManager.addEntry(path, false);
        contextManager.setFileContent(path, content);
    }
    
    /**
     * Remove a file from the dynamic context.
     */
    public void removeContextFile(String path) {
        contextManager.removeEntry(path);
    }
    
    /**
     * Get the current conversation history.
     */
    public List<ChatMessage> getHistory() {
        return new ArrayList<>(history.getMessages());
    }
    
    /**
     * Clear the conversation history (keeps system message).
     */
    public void clearHistory() {
        history.clear();
    }
    
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        
        // Add context files
        String contextBlock = contextManager.buildContextBlock();
        if (!contextBlock.isEmpty()) {
            sb.append(contextBlock).append("\n\n");
        }
        
        // Add tool descriptions
        sb.append("Available tools:\n");
        for (AgentTool tool : tools) {
            sb.append("- ").append(tool.getName()).append(": ")
              .append(tool.getDescription()).append("\n");
        }
        
        return sb.toString();
    }
    
    private LlmResponse callLlm(List<ChatMessage> messages, List<AgentTool> tools) {
        // Implementation would use actual LLM API
        // This is a placeholder showing the structure
        throw new UnsupportedOperationException("Implement with your LLM provider");
    }
    
    private AgentTool findTool(String name) {
        return tools.stream()
            .filter(t -> t.getName().equals(name))
            .findFirst()
            .orElse(null);
    }
}

/**
 * Callback interface for streaming responses.
 */
public interface AgentCallback {
    
    /**
     * Status update (e.g., "Reading file...", "Calling LLM...")
     */
    void onStatus(String message);
    
    /**
     * Thinking/reasoning output from the model.
     */
    void onThinking(String reasoning);
    
    /**
     * Response chunk (for streaming responses).
     */
    void onResponseChunk(String chunk);
    
    /**
     * Tool call is starting.
     */
    void onToolStart(String toolName, String description);
    
    /**
     * Tool call completed.
     */
    void onToolEnd(String toolName, boolean success);
    
    /**
     * Token usage information.
     */
    void onUsage(int promptTokens, int completionTokens, int contextWindow);
}

/**
 * Configuration for the agent.
 */
public class AgentConfig {
    private String model = "gpt-4";
    private int maxIterations = 50;
    private int maxOutputTokens = 8192;
    
    public AgentConfig setModel(String model) {
        this.model = model;
        return this;
    }
    
    public AgentConfig setMaxIterations(int iterations) {
        this.maxIterations = iterations;
        return this;
    }
    
    public AgentConfig setMaxOutputTokens(int tokens) {
        this.maxOutputTokens = tokens;
        return this;
    }
    
    public String getModel() { return model; }
    public int getMaxIterations() { return maxIterations; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
}
```

---

## Package: copilot.core.chat

### ChatMessage

```java
package copilot.core.chat;

import java.util.List;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

/**
 * Represents a single chat message in the conversation history.
 */
public class ChatMessage {
    
    public enum Role {
        SYSTEM,     // System instructions
        USER,       // User input
        ASSISTANT,  // Assistant response
        TOOL        // Tool call results
    }
    
    private final Role role;
    private final String content;
    private final List<ToolCall> toolCalls;
    
    /**
     * Create a system message.
     */
    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null);
    }
    
    /**
     * Create a user message.
     */
    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null);
    }
    
    /**
     * Create an assistant message with optional tool calls.
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, null);
    }
    
    /**
     * Create an assistant message with tool calls.
     */
    public static ChatMessage assistantWithToolCalls(
            JsonArray toolCalls, 
            String narration) {
        List<ToolCall> calls = parseToolCalls(toolCalls);
        return new ChatMessage(Role.ASSISTANT, 
            narration != null ? narration : "", 
            calls);
    }
    
    /**
     * Create a tool result message.
     */
    public static ChatMessage toolResult(
            String callId,
            String toolName,
            String result) {
        // Tool results are stored in the conversation history
        // but have a special role
        return new ChatMessage(Role.TOOL, 
            formatToolResult(callId, toolName, result), 
            null);
    }
    
    public Role getRole() { return role; }
    public String getContent() { return content; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    
    /**
     * Check if this message contains tool calls.
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
    
    private static String formatToolResult(String callId, String toolName, 
                                           String result) {
        JsonObject obj = new JsonObject();
        obj.addProperty("call_id", callId);
        obj.addProperty("tool_name", toolName);
        obj.addProperty("result", result);
        return obj.toString();
    }
    
    private static List<ToolCall> parseToolCalls(JsonArray array) {
        // Parse JSON array of tool calls into ToolCall objects
        // Implementation depends on your JSON structure
        return new ArrayList<>();
    }
}

/**
 * Represents a single tool call within an assistant message.
 */
public class ToolCall {
    private final String id;
    private final String name;
    private final JsonObject arguments;
    
    public ToolCall(String id, String name, JsonObject arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }
    
    public String getId() { return id; }
    public String getName() { return name; }
    public JsonObject getArguments() { return arguments; }
}
```

### ConversationHistory

```java
package copilot.core.chat;

import java.util.List;
import java.util.ArrayList;

/**
 * Manages the conversation history for an agent session.
 */
public class ConversationHistory {
    
    private final List<ChatMessage> messages = new ArrayList<>();
    private int maxMessages = 60;
    
    /**
     * Add a message to the history.
     */
    public void add(ChatMessage message) {
        messages.add(message);
        trim();
    }
    
    /**
     * Get all messages in history.
     */
    public List<ChatMessage> getMessages() {
        return new ArrayList<>(messages);
    }
    
    /**
     * Set or replace the system message.
     */
    public void setSystemMessage(String content) {
        if (!messages.isEmpty() && 
            messages.get(0).getRole() == ChatMessage.Role.SYSTEM) {
            messages.set(0, ChatMessage.system(content));
        } else {
            messages.add(0, ChatMessage.system(content));
        }
    }
    
    /**
     * Remove tool call and tool result messages from history.
     */
    public void pruneToolMessages() {
        messages.removeIf(m -> m.hasToolCalls() || 
                           m.getRole() == ChatMessage.Role.TOOL);
    }
    
    /**
     * Keep only the most recent N tool pairs, removing older ones.
     * 
     * <p>This helps manage context size while preserving recent
     * tool interaction history.
     */
    public void pruneOldToolPairs(int keepRecentPairs) {
        // Find all tool call/result pairs
        List<List<Integer>> pairs = findToolPairs();
        
        int toRemove = pairs.size() - keepRecentPairs;
        if (toRemove <= 0) return;
        
        // Mark pairs for removal
        java.util.Set<Integer> removeIndices = new java.util.HashSet<>();
        for (int i = 0; i < toRemove; i++) {
            for (int idx : pairs.get(i)) {
                removeIndices.add(idx);
            }
        }
        
        // Remove marked messages
        List<ChatMessage> kept = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (!removeIndices.contains(i)) {
                kept.add(messages.get(i));
            }
        }
        messages.clear();
        messages.addAll(kept);
    }
    
    /**
     * Replace all messages with a compressed set.
     */
    public void replaceMessages(List<ChatMessage> newMessages) {
        ChatMessage systemMsg = findSystemMessage();
        messages.clear();
        if (systemMsg != null) {
            messages.add(systemMsg);
        }
        messages.addAll(newMessages);
    }
    
    /**
     * Clear all messages except the system message.
     */
    public void clear() {
        ChatMessage systemMsg = findSystemMessage();
        messages.clear();
        if (systemMsg != null) {
            messages.add(systemMsg);
        }
    }
    
    private void trim() {
        while (messages.size() > maxMessages) {
            int removeIdx = (messages.get(0).getRole() == ChatMessage.Role.SYSTEM) 
                ? 1 : 0;
            if (removeIdx < messages.size()) {
                messages.remove(removeIdx);
            } else {
                break;
            }
        }
    }
    
    private ChatMessage findSystemMessage() {
        return messages.stream()
            .filter(m -> m.getRole() == ChatMessage.Role.SYSTEM)
            .findFirst().orElse(null);
    }
    
    private List<List<Integer>> findToolPairs() {
        // Group contiguous (assistant-with-tool-calls + tool-result) pairs
        List<List<Integer>> pairs = new ArrayList<>();
        List<Integer> currentPair = null;
        
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            
            if (msg.hasToolCalls()) {
                currentPair = new ArrayList<>();
                currentPair.add(i);
            } else if (msg.getRole() == ChatMessage.Role.TOOL 
                       && currentPair != null) {
                currentPair.add(i);
                pairs.add(currentPair);
                currentPair = null;
            }
        }
        
        return pairs;
    }
}
```

---

## Package: copilot.core.context

### ContextManager

```java
package copilot.core.context;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

/**
 * Manages the set of files available to the agent.
 */
public class ContextManager {
    
    private final List<WatchedEntry> entries = new ArrayList<>();
    private final Map<String, String> fileContents = new HashMap<>();
    
    /**
     * Add a file or folder to be tracked.
     */
    public void addEntry(String path, boolean isFolder) {
        if (!entries.stream().anyMatch(e -> e.path.equals(path))) {
            entries.add(new WatchedEntry(path, isFolder));
        }
    }
    
    /**
     * Remove a tracked entry.
     */
    public void removeEntry(String path) {
        entries.removeIf(e -> e.path.equals(path));
    }
    
    /**
     * Get all currently watched entries.
     */
    public List<WatchedEntry> getEntries() {
        return new ArrayList<>(entries);
    }
    
    /**
     * Set the content of a file for context building.
     */
    public void setFileContent(String path, String content) {
        fileContents.put(path, content);
    }
    
    /**
     * Build the context block for injection into system prompt.
     */
    public String buildContextBlock() {
        StringBuilder sb = new StringBuilder();
        
        for (WatchedEntry entry : entries) {
            if (entry.isFolder()) {
                collectFiles(entry.path, sb);
            } else {
                appendFile(entry.path, sb);
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Get the content of a specific file.
     */
    public String getFileContent(String path) throws Exception {
        if (fileContents.containsKey(path)) {
            return fileContents.get(path);
        }
        throw new Exception("File not in context: " + path);
    }
    
    private void collectFiles(String folderPath, StringBuilder sb) {
        // Recursively collect files from folder
        // Implementation depends on your file system access
    }
    
    private void appendFile(String filePath, StringBuilder sb) {
        String content = fileContents.get(filePath);
        if (content != null) {
            sb.append("## ").append(filePath).append("\n");
            sb.append("```").append(getLanguageTag(filePath)).append("\n");
            sb.append(content).append("\n");
            sb.append("```\n\n");
        }
    }
    
    private String getLanguageTag(String filePath) {
        // Return language tag based on file extension
        if (filePath.endsWith(".java")) return "java";
        if (filePath.endsWith(".kt")) return "kotlin";
        if (filePath.endsWith(".py")) return "python";
        if (filePath.endsWith(".js") || filePath.endsWith(".ts")) return "javascript";
        return "";
    }
}

/**
 * Entry representing a tracked file or folder.
 */
public static class WatchedEntry {
    private final String path;
    private final boolean isFolder;
    
    public WatchedEntry(String path, boolean isFolder) {
        this.path = path;
        this.isFolder = isFolder;
    }
    
    public String getPath() { return path; }
    public boolean isFolder() { return isFolder; }
}
```

---

## Package: copilot.core.tools

### AgentTool Interface

```java
package copilot.core.tools;

import com.google.gson.JsonObject;

/**
 * Interface for all agent tools.
 */
public interface AgentTool {
    
    /**
     * Unique identifier for this tool.
     */
    String getName();
    
    /**
     * Human-readable description of what this tool does.
     */
    String getDescription();
    
    /**
     * JSON Schema describing the expected parameters.
     */
    JsonObject getParameterSchema();
    
    /**
     * Execute the tool with the given parameters.
     * 
     * @param params The parsed JSON parameters
     * @return The result as a string (will be shown to the model)
     */
    String execute(JsonObject params) throws Exception;
    
    /**
     * Optional: Status message to show while tool is running.
     */
    default String getStatusMessage(JsonObject args) {
        return getName();
    }
    
    /**
     * Optional: Whether to show result in chat UI.
     */
    default boolean shouldShowResultInChat() {
        return false;
    }
}

/**
 * Base class for common tool patterns.
 */
public abstract class AbstractTool implements AgentTool {
    
    protected String name;
    protected String description;
    
    public AbstractTool(String name, String description) {
        this.name = name;
        this.description = description;
    }
    
    @Override
    public String getName() { return name; }
    
    @Override
    public String getDescription() { return description; }
    
    @Override
    public JsonObject getParameterSchema() {
        // Default implementation - can be overridden
        return new JsonObject();
    }
}
```

### PathGuard

```java
package copilot.core.tools;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Path validation and security utilities.
 */
public class PathGuard {
    
    private static final Set<String> ALLOWED_EXTENSIONS = 
        Set.of("java", "kt", "py", "js", "ts", "xml", "json", "md", "txt");
    
    /**
     * Validate that a path is safe to use.
     * 
     * @param basePath The project base path
     * @param userPath The user-provided path
     * @return null if valid, error message if invalid
     */
    public static String check(String basePath, String userPath) {
        // Normalize paths
        Path base = Paths.get(basePath).normalize();
        Path path = Paths.get(userPath).normalize();
        
        // Check for absolute paths (not allowed)
        if (path.isAbsolute()) {
            return "Absolute paths not allowed";
        }
        
        // Check for parent directory references
        String normalized = path.toString();
        if (normalized.contains("..") || normalized.contains("./")) {
            return "Parent directory references not allowed";
        }
        
        // Check extension
        String ext = getFileExtension(path.toString());
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            return "File type not allowed: ." + ext;
        }
        
        // Resolve and verify within base
        Path resolved = base.resolve(path).normalize();
        if (!resolved.startsWith(base.toString() + java.io.File.separator) 
            && !resolved.equals(base)) {
            return "Path outside allowed directory";
        }
        
        return null; // Valid
    }
    
    /**
     * Safely resolve a path within the base directory.
     */
    public static Path resolve(String basePath, String userPath) {
        String error = check(basePath, userPath);
        if (error != null) {
            throw new SecurityException(error);
        }
        return Paths.get(basePath).resolve(userPath).normalize();
    }
    
    private static String getFileExtension(String path) {
        int lastDot = path.lastIndexOf('.');
        return lastDot > 0 ? path.substring(lastDot + 1).toLowerCase() : "";
    }
}
```

### ProcessRunner

```java
package copilot.core.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Safe process execution wrapper with timeout support.
 */
public class ProcessRunner {
    
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    
    /**
     * Execute a command with optional timeout.
     * 
     * @param command The command to execute
     * @param workingDir The working directory
     * @param timeoutSeconds Timeout in seconds (use DEFAULT_TIMEOUT_SECONDS for default)
     * @return ProcessResult with exit code and output
     */
    public static ProcessResult run(String[] command, java.io.File workingDir, 
                                     int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) {
                pb.directory(workingDir);
            }
            pb.redirectErrorStream(true);  // Merge stderr into stdout
            
            Process process = pb.start();
            
            // Read output in separate thread
            StringBuilder output = new StringBuilder();
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = 
                        new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (IOException ignored) {}
            });
            
            outputThread.start();
            
            // Wait with timeout
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            
            if (!finished) {
                process.destroyForcibly();
                return ProcessResult.timeout(command, timeoutSeconds);
            }
            
            outputThread.join();  // Ensure all output captured
            
            int exitCode = process.exitValue();
            return new ProcessResult(exitCode, output.toString(), null);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProcessResult.interrupted(command);
        } catch (IOException e) {
            return ProcessResult.error(command, e.getMessage());
        }
    }
    
    public static class ProcessResult {
        private final int exitCode;
        private final String output;
        private final boolean timedOut;
        
        public ProcessResult(int exitCode, String output, Boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output;
            this.timedOut = timedOut != null && timedOut;
        }
        
        public static ProcessResult timeout(String[] cmd, int seconds) {
            return new ProcessResult(-1,
                "Process timed out after " + seconds + " seconds",
                true);
        }
        
        public static ProcessResult interrupted(String[] cmd) {
            return new ProcessResult(-1, "Process was interrupted", false);
        }
        
        public static ProcessResult error(String[] cmd, String message) {
            return new ProcessResult(-1, "Error: " + message, false);
        }
        
        public int getExitCode() { return exitCode; }
        public String getOutput() { return output; }
        public boolean isTimedOut() { return timedOut; }
    }
}
```

---

## Package: copilot.core.memory

```java
package copilot.core.memory;

import java.util.List;
import java.util.ArrayList;

/**
 * Stores and retrieves semantic facts across sessions.
 */
public class MemoryStore {
    
    private final List<MemoryFact> facts = new ArrayList<>();
    private int maxFacts = 100;
    
    /**
     * Add a fact to memory.
     */
    public void add(String text, List<String> fileTags) {
        MemoryFact fact = new MemoryFact(
            generateId(),
            text,
            fileTags,
            System.currentTimeMillis()
        );
        
        facts.add(fact);
        if (facts.size() > maxFacts) {
            // Remove oldest facts
            facts.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));
            facts.subList(0, facts.size() - maxFacts).clear();
        }
    }
    
    /**
     * Search for relevant facts.
     */
    public List<MemoryFact> search(String query, int limit) {
        return facts.stream()
            .filter(f -> f.getText().toLowerCase().contains(query.toLowerCase()))
            .limit(limit)
            .toList();
    }
    
    /**
     * Get all facts.
     */
    public List<MemoryFact> getAllFacts() {
        return new ArrayList<>(facts);
    }
    
    private String generateId() {
        return Long.toHexString(System.currentTimeMillis());
    }
}

/**
 * A single memory fact.
 */
public class MemoryFact {
    private final String id;
    private final String text;
    private final List<String> fileTags;
    private final long timestamp;
    
    public MemoryFact(String id, String text, List<String> fileTags, 
                      long timestamp) {
        this.id = id;
        this.text = text;
        this.fileTags = new ArrayList<>(fileTags);
        this.timestamp = timestamp;
    }
    
    public String getId() { return id; }
    public String getText() { return text; }
    public List<String> getFileTags() { return fileTags; }
    public long getTimestamp() { return timestamp; }
}
```

---

## Package: copilot.core.review

```java
package copilot.core.review;

import java.util.List;
import java.util.ArrayList;

/**
 * Represents a pending change to be reviewed.
 */
public class PendingChange {
    
    public enum ChangeType { ADD, MODIFY, DELETE }
    
    private final String filePath;
    private final String fullPath;
    private final String originalContent;
    private final String newContent;
    private final ChangeType type;
    
    public PendingChange(String filePath, String fullPath,
                         String originalContent, String newContent,
                         ChangeType type) {
        this.filePath = filePath;
        this.fullPath = fullPath;
        this.originalContent = originalContent;
        this.newContent = newContent;
        this.type = type;
    }
    
    public String getFilePath() { return filePath; }
    public String getFullPath() { return fullPath; }
    public String getOriginalContent() { return originalContent; }
    public String getNewContent() { return newContent; }
    public ChangeType getType() { return type; }
}

/**
 * Represents a single line in a diff.
 */
public class DiffLine {
    
    public enum Type { CONTEXT, ADDITION, DELETION }
    
    private final Type type;
    private final String content;
    private final int originalLine;
    private final int newLine;
    
    public DiffLine(Type type, String content, int originalLine, int newLine) {
        this.type = type;
        this.content = content;
        this.originalLine = originalLine;
        this.newLine = newLine;
    }
    
    public Type getType() { return type; }
    public String getContent() { return content; }
    public int getOriginalLine() { return originalLine; }
    public int getNewLine() { return newLine; }
}

/**
 * Represents a hunk of changes.
 */
public class DiffHunk {
    private final int headerStart;
    private final int headerEnd;
    private final List<DiffLine> lines;
    
    public DiffHunk(int headerStart, int headerEnd, List<DiffLine> lines) {
        this.headerStart = headerStart;
        this.headerEnd = headerEnd;
        this.lines = new ArrayList<>(lines);
    }
    
    public int getHeaderStart() { return headerStart; }
    public int getHeaderEnd() { return headerEnd; }
    public List<DiffLine> getLines() { return new ArrayList<>(lines); }
}

/**
 * Computes diffs between file contents.
 */
public class DiffComputer {
    
    /**
     * Compute a line-by-line diff between two strings.
     */
    public static List<DiffHunk> compute(String original, String modified) {
        // Simple implementation using character-by-character comparison
        // For production use, consider a proper diff algorithm ( Myers, etc. )
        
        List<DiffLine> lines = new ArrayList<>();
        
        int origIdx = 0;
        int modIdx = 0;
        
        while (origIdx < original.length() || modIdx < modified.length()) {
            if (origIdx < original.length() && modIdx < modified.length()) {
                if (original.charAt(origIdx) == modified.charAt(modIdx)) {
                    lines.add(new DiffLine(DiffLine.Type.CONTEXT, 
                        String.valueOf(original.charAt(origIdx)), origIdx, modIdx));
                    origIdx++;
                    modIdx++;
                } else {
                    // Deletion
                    lines.add(new DiffLine(DiffLine.Type.DELETION,
                        String.valueOf(original.charAt(origIdx)), origIdx, -1));
                    origIdx++;
                }
            } else if (origIdx < original.length()) {
                lines.add(new DiffLine(DiffLine.Type.DELETION,
                    String.valueOf(original.charAt(origIdx)), origIdx, -1));
                origIdx++;
            } else {
                lines.add(new DiffLine(DiffLine.Type.ADDITION,
                    String.valueOf(modified.charAt(modIdx)), -1, modIdx));
                modIdx++;
            }
        }
        
        // Group into hunks (simplified)
        List<DiffHunk> hunks = new ArrayList<>();
        if (!lines.isEmpty()) {
            hunks.add(new DiffHunk(0, lines.size() - 1, lines));
        }
        
        return hunks;
    }
}
```

---

## Usage Example

```java
import copilot.core.agent.AgentSession;
import copilot.core.agent.AgentConfig;
import copilot.core.tools.AgentTool;
import com.google.gson.JsonObject;

public class CopilotExample {
    
    public static void main(String[] args) {
        // Create configuration
        AgentConfig config = new AgentConfig()
            .setModel("gpt-4")
            .setMaxIterations(50)
            .setMaxOutputTokens(8192);
        
        // Create session
        AgentSession session = new AgentSession(config);
        
        // Add a custom tool
        session.addTool(new AgentTool() {
            @Override
            public String getName() { return "get_project_info"; }
            
            @Override
            public String getDescription() { 
                return "Get information about the current project";
            }
            
            @Override
            public JsonObject getParameterSchema() {
                return new JsonObject();
            }
            
            @Override
            public String execute(JsonObject params) {
                // Your tool implementation
                return "{\"name\": \"MyProject\", \"version\": \"1.0.0\"}";
            }
        });
        
        // Add context files
        session.addContextFile("src/main/Foo.java", 
            "public class Foo {\n  public void bar() {}\n}");
        
        // Run agent
        String response = session.chat(
            "What is the project structure?", 
            new AgentCallback() {
                @Override
                public void onStatus(String message) {
                    System.out.println("[STATUS] " + message);
                }
                
                @Override
                public void onThinking(String reasoning) {
                    System.out.println("[THINKING] " + reasoning);
                }
                
                @Override
                public void onResponseChunk(String chunk) {
                    System.out.print(chunk);
                }
                
                // Implement other methods...
            }
        );
        
        System.out.println("\n[FINAL] " + response);
    }
}
```

---

## API Stability

### Stable APIs (v0.1)
- `AgentSession` - Core agent functionality
- `ChatMessage` / `ConversationHistory` - Message management
- `ContextManager` - File tracking
- `AgentTool` - Tool interface
- `PathGuard` - Security utilities
- `ProcessRunner` - Process execution

### Experimental APIs (may change)
- Memory store implementation details
- Diff computation algorithms
- Compression strategies

---

*This API reference will be updated as the library evolves.*
