# Copilot-Base API Design

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                     copilot-base (NEW)                              │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  copilot.core.agent                                           │  │
│  │  ├── AgentSession          ← Core agentic loop               │  │
│  │  ├── AgentCallback         ← Streaming callbacks             │  │
│  │  ├── Phase                 ← Phase enum                      │  │
│  │  └── PhaseController       ← State management                │  │
│  ├───────────────────────────────────────────────────────────────┤  │
│  │  copilot.core.chat                                            │  │
│  │  ├── ChatMessage           ← Message model                   │  │
│  │  ├── ConversationHistory   ← Message history                 │  │
│  │  └── ChatMode              ← Mode configuration              │  │
│  ├───────────────────────────────────────────────────────────────┤  │
│  │  copilot.core.context                                         │  │
│  │  ├── ContextManager        ← File tracking (core)            │  │
│  │  ├── StrippedGenerator     ← Code stripping                  │  │
│  │  └── LanguageStripper      ← Language processors             │  │
│  ├───────────────────────────────────────────────────────────────┤  │
│  │  copilot.core.tools                                           │  │
│  │  ├── AgentTool             ← Tool interface                  │  │
│  │  ├── PathGuard             ← Security utilities              │  │
│  │  └── ProcessRunner         ← Process execution               │  │
│  ├───────────────────────────────────────────────────────────────┤  │
│  │  copilot.core.memory                                          │  │
│  │  ├── MemoryFact            ← Fact model                      │  │
│  │  └── MemoryStore           ← Fact storage                    │  │
│  ├───────────────────────────────────────────────────────────────┤  │
│  │  copilot.core.review                                          │  │
│  │  ├── PendingChange         ← Change tracking                 │  │
│  │  └── Diff*                 ← Diff computation                │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                     copilot-intellij (NEW)                          │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  copilot.intellij.ui                                          │  │
│  │  ├── CopilotChatPanel      ← ToolWindow UI                   │  │
│  │  ├── AgentPlanPanel        ← Plan display                    │  │
│  │  └── ...                   ← Other IntelliJ-specific UI      │  │
│  ├───────────────────────────────────────────────────────────────┤  │
│  │  copilot.intellij.context                                     │  │
│  │  ├── IntelliJContextManager ← VFS integration                │  │
│  │  └── ...                   ← IDE-specific context            │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                     copilot-eclipse (NEW)                           │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  copilot.eclipse.ui                                           │  │
│  │  ├── CopilotChatView       ← Eclipse view                    │  │
│  │  └── ...                   ← Eclipse-specific UI             │  │
│  ├───────────────────────────────────────────────────────────────┤  │
│  │  copilot.eclipse.context                                      │  │
│  │  ├── EclipseContextManager ← JDT integration                 │  │
│  │  └── ...                   ← IDE-specific context            │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Core API Design

### AgentSession (Core Engine)

```java
package copilot.core.agent;

import java.util.List;
import java.util.function.Consumer;

/**
 * The core agentic session that drives the AI coding assistant.
 * This is the main entry point for using copilot-base.
 */
public class AgentSession {
    
    /**
     * Callback interface for streaming responses.
     */
    public interface Callback {
        void onStatus(String message);
        void onThinking(String reasoning);
        void onResponseChunk(String chunk);
        void onToolStart(String toolName, String description);
        void onToolEnd(String toolName, boolean success);
        void onToolError(String toolName, String error);
        void onUsage(int promptTokens, int completionTokens, int contextWindow);
    }
    
    /**
     * Create a new agent session.
     */
    public AgentSession(AgentConfig config) {
        // Initialize with configuration
    }
    
    /**
     * Send a message and get a response.
     * This is the main entry point for the agentic loop.
     */
    public String chat(String userMessage, Callback callback) {
        // Implementation drives the agent loop:
        // 1. Build context from pinned files
        // 2. Send to LLM with tools available
        // 3. Handle tool calls
        // 4. Return final response
    }
    
    /**
     * Pin a file for dynamic context.
     */
    public void addContextFile(String filePath, String content) {
        // Store file content for inclusion in system prompt
    }
    
    /**
     * Remove a file from context.
     */
    public void removeContextFile(String filePath) {
        // Remove from context storage
    }
    
    /**
     * Get the current conversation history.
     */
    public List<ChatMessage> getHistory() {
        return conversationHistory.getMessages();
    }
    
    /**
     * Clear the conversation history.
     */
    public void clearHistory() {
        conversationHistory.clear();
    }
}
```

### AgentTool (Tool Interface)

```java
package copilot.core.tools;

import com.google.gson.JsonObject;
import java.util.Map;

/**
 * Interface for all agent tools.
 * Tools are functions that the AI model can call to interact with the environment.
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
        // Default implementation builds schema from annotations or reflection
        return new JsonObject();
    }
}
```

### ContextManager (File Tracking)

```java
package copilot.core.context;

import java.util.List;
import java.util.Map;

/**
 * Manages the set of files that are available to the agent.
 * This is the agnostic core - IDE-specific implementations add VFS integration.
 */
public class ContextManager {
    
    /**
     * Entry representing a tracked file or folder.
     */
    public static class WatchedEntry {
        private final String relativePath;
        private final boolean isFolder;
        
        public WatchedEntry(String path, boolean isFolder) {
            this.relativePath = path;
            this.isFolder = isFolder;
        }
        
        public String getRelativePath() { return relativePath; }
        public boolean isFolder() { return isFolder; }
    }
    
    /**
     * Add a file or folder to be tracked.
     */
    public void addEntry(String relativePath, boolean isFolder) {
        // Store entry for context building
    }
    
    /**
     * Remove a tracked entry.
     */
    public void removeEntry(String relativePath) {
        // Remove from tracking
    }
    
    /**
     * Get all currently watched entries.
     */
    public List<WatchedEntry> getEntries() {
        return new ArrayList<>(entries);
    }
    
    /**
     * Build the context block for injection into system prompt.
     * This is what gets sent to the LLM.
     */
    public String buildContextBlock() {
        StringBuilder sb = new StringBuilder();
        
        for (WatchedEntry entry : entries) {
            if (entry.isFolder()) {
                // Recursively collect files in folder
                collectFiles(entry.getRelativePath(), sb);
            } else {
                // Add single file
                appendFile(entry.getRelativePath(), sb);
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Get the content of a specific file.
     */
    public String getFileContent(String relativePath) throws Exception {
        // Read from storage or VFS
    }
    
    private void collectFiles(String folderPath, StringBuilder sb) {
        // Implementation collects all files in folder
    }
    
    private void appendFile(String filePath, StringBuilder sb) {
        // Implementation appends file with markdown fence
    }
}
```

### ConversationHistory (Message Management)

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
     * Set the system message (always at index 0).
     */
    public void setSystemMessage(String content) {
        if (!messages.isEmpty() && messages.get(0).getRole() == ChatMessage.Role.SYSTEM) {
            messages.set(0, ChatMessage.system(content));
        } else {
            messages.add(0, ChatMessage.system(content));
        }
    }
    
    /**
     * Remove tool call/result pairs to reduce context size.
     */
    public void pruneToolMessages() {
        messages.removeIf(m -> m.hasToolCalls() || m.getRole() == ChatMessage.Role.TOOL);
    }
    
    /**
     * Keep only the most recent N tool pairs, removing older ones.
     */
    public void pruneOldToolPairs(int keepRecentPairs) {
        // Implementation groups and removes old pairs
    }
    
    /**
     * Replace all messages with a compressed set.
     */
    public void replaceMessages(List<ChatMessage> newMessages) {
        ChatMessage systemMsg = findSystemMessage();
        messages.clear();
        if (systemMsg != null) messages.add(systemMsg);
        messages.addAll(newMessages);
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
    
    private ChatMessage findSystemMessage() {
        return messages.stream()
            .filter(m -> m.getRole() == ChatMessage.Role.SYSTEM)
            .findFirst().orElse(null);
    }
}
```

### MemoryStore (Semantic Memory)

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
        // Simple keyword matching - can be enhanced with embeddings
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
    
    public MemoryFact(String id, String text, List<String> fileTags, long timestamp) {
        this.id = id;
        this.text = text;
        this.fileTags = new ArrayList<>(fileTags);
        this.timestamp = timestamp;
    }
    
    // Getters...
}
```

---

## IDE Adapter Pattern

### IntelliJ Integration

```java
package copilot.intellij.context;

import copilot.core.context.ContextManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;

/**
 * IntelliJ-specific context manager with VFS integration.
 */
public class IntelliJContextManager extends ContextManager {
    
    private final Project project;
    private com.intellij.openapi.Disposable disposable;
    
    public IntelliJContextManager(Project project) {
        this.project = project;
        
        // Register VFS listener for real-time updates
        disposable = project.getMessageBus().connect();
        disposable.subscribe(VirtualFile.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    VirtualFile vf = event.getFile();
                    if (vf != null && isWatched(vf.getPath())) {
                        regenerateSnapshot(vf.getPath());
                    }
                }
            }
        });
    }
    
    @Override
    protected String readFile(VirtualFile file) {
        // Use IntelliJ VFS API
        return new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
    }
    
    private boolean isWatched(String path) {
        // Check if this path is in our watched entries
        return getEntries().stream()
            .anyMatch(e -> e.getRelativePath().equals(path));
    }
    
    @Override
    public void dispose() {
        if (disposable != null) {
            disposable.dispose();
        }
    }
}
```

### Eclipse Integration (Conceptual)

```java
package copilot.eclipse.context;

import copilot.core.context.ContextManager;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

/**
 * Eclipse-specific context manager with JDT integration.
 */
public class EclipseContextManager extends ContextManager {
    
    private final IProject project;
    private IResourceChangeListener listener;
    
    public EclipseContextManager(IProject project) {
        this.project = project;
        
        // Register resource change listener
        listener = event -> {
            for (IResourceDelta delta : event.getAffectedChildren()) {
                if (delta.getResource() instanceof IFile) {
                    IFile file = (IFile) delta.getResource();
                    if (isWatched(file.getProjectRelativePath().toString())) {
                        regenerateSnapshot(file);
                    }
                }
            }
        };
        
        try {
            ResourcesPlugin.getWorkspace().addResourceChangeListener(listener);
        } catch (CoreException e) {
            // Handle error
        }
    }
    
    @Override
    protected String readFile(IFile file) {
        // Use JDT API to read file
        try {
            return new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (CoreException e) {
            throw new RuntimeException(e);
        }
    }
    
    private boolean isWatched(String path) {
        return getEntries().stream()
            .anyMatch(e -> e.getRelativePath().equals(path));
    }
    
    @Override
    public void dispose() {
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(listener);
    }
}
```

---

## Usage Examples

### Basic Agent Session

```java
import copilot.core.agent.AgentSession;
import copilot.core.agent.AgentCallback;

public class ExampleUsage {
    
    public static void main(String[] args) {
        // Create configuration
        AgentConfig config = new AgentConfig()
            .setModel("gpt-4")
            .setMaxIterations(50)
            .setMaxOutputTokens(8192);
        
        // Create session
        AgentSession session = new AgentSession(config);
        
        // Set up callback
        AgentCallback callback = new AgentCallback() {
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
            
            // ... other methods
        };
        
        // Run agent
        String response = session.chat("Explain how to use this API", callback);
        
        System.out.println("\n[FINAL] " + response);
    }
}
```

### With Context Files

```java
AgentSession session = new AgentSession(config);

// Add context files
session.addContextFile("src/main/Foo.java", 
    "public class Foo {\n  public void bar() {}\n}");
session.addContextFile("README.md", "# Project\nThis is a project.");

// Now the agent has access to these files
String response = session.chat("What classes are in this project?", callback);
```

### With Tools

```java
import copilot.core.tools.AgentTool;
import com.google.gson.JsonObject;

// Define custom tools
AgentTool readFileTool = new AgentTool() {
    @Override
    public String getName() { return "read_file"; }
    
    @Override
    public String getDescription() { 
        return "Read a file from the project."; 
    }
    
    @Override
    public JsonObject getParameterSchema() {
        // Define JSON schema...
        return new JsonObject();
    }
    
    @Override
    public String execute(JsonObject params) {
        String path = params.get("path").getAsString();
        // Read and return file content
        return "File contents...";
    }
};

// Add tools to session
session.addTool(readFileTool);

// Agent can now call this tool
String response = session.chat("Read the main class", callback);
```

---

## Migration Path

### Step 1: Extract Core (Current)
- [ ] Create `copilot-base` module
- [ ] Move all Category 1 files
- [ ] Update imports in `copilot` module
- [ ] Verify IntelliJ plugin still works

### Step 2: Refactor ContextManager
- [ ] Split into core + IDE adapter
- [ ] Implement `IntelliJContextManager`
- [ ] Test with existing functionality

### Step 3: Create Eclipse Integration
- [ ] Implement `EclipseContextManager`
- [ ] Implement Eclipse UI components
- [ ] Test basic functionality

### Step 4: Stabilize and Document
- [ ] API documentation
- [ ] Example implementations
- [ ] Migration guide for existing users

---

## Testing Strategy

```kotlin
// copilot-base/src/test/kotlin/copilot/core/agent/AgentSessionTest.kt
class AgentSessionTest {
    
    @Test
    fun `chat should return response`() {
        val config = AgentConfig().setModel("test")
        val session = AgentSession(config)
        
        val response = session.chat("Hello", mockCallback())
        
        assertNotNull(response)
        assertTrue(response.isNotEmpty())
    }
    
    @Test
    fun `addContextFile should make file available`() {
        val session = AgentSession(AgentConfig())
        
        session.addContextFile("test.java", "public class Test {}")
        
        // Verify file is in context
        val context = session.getContextBlock()
        assertTrue(context.contains("test.java"))
    }
    
    @Test
    fun `pruneToolMessages should remove tool calls`() {
        val history = ConversationHistory()
        
        history.add(ChatMessage.assistantWithToolCalls(...))
        history.add(ChatMessage.toolResult(...))
        history.add(ChatMessage.user("Next message"))
        
        history.pruneToolMessages()
        
        // Verify tool messages removed
        assertTrue(history.getMessages().noneMatch { it.hasToolCalls() })
    }
}
```

---

## Summary

| Component | Status | Notes |
|-----------|--------|-------|
| `AgentSession` | Ready | Core logic unchanged |
| `ConversationHistory` | Ready | No external deps |
| `ChatMessage` | Ready | Pure data model |
| `ContextManager` | Partial | Needs adapter pattern |
| `AgentTool` | Ready | Interface only |
| `MemoryStore` | Ready | Simple in-memory impl |
| `DiffComputer` | Ready | Algorithmic, no deps |

**Estimated Effort:**
- Core extraction: 2 days
- Adapter implementation: 3 days  
- Eclipse integration: 5 days
- Testing & docs: 3 days

**Total: ~13 days**
