# Copilot-Base Module Structure

## Directory Layout

```
copilot-base/
├── src/
│   └── main/
│       └── java/
│           └── copilot/
│               ├── core/
│               │   ├── agent/
│               │   │   ├── AgentSession.java
│               │   │   ├── AgentCallback.java
│               │   │   ├── Phase.java
│               │   │   └── PhaseController.java
│               │   ├── chat/
│               │   │   ├── ChatMessage.java
│               │   │   ├── ConversationHistory.java
│               │   │   └── ChatMode.java
│               │   ├── context/
│               │   │   ├── ContextManager.java
│               │   │   ├── StrippedGenerator.java
│               │   │   ├── LanguageStripper.java
│               │   │   └── BraceLanguageStripper.java
│               │   ├── tools/
│               │   │   ├── api/
│               │   │   │   ├── AgentTool.java
│               │   │   │   ├── PathGuard.java
│               │   │   │   └── ProcessRunner.java
│               │   │   ├── impl/
│               │   │   │   ├── file/
│               │   │   │   │   ├── GetCurrentFileTool.java
│               │   │   │   │   ├── ListFilesTool.java
│               │   │   │   │   ├── ReadFileTool.java
│               │   │   │   │   ├── ReplaceInFileTool.java
│               │   │   │   │   ├── CreateFileTool.java
│               │   │   │   │   └── ...
│               │   │   │   ├── search/
│               │   │   │   │   ├── SearchInFilesTool.java
│               │   │   │   │   └── ScanProblemsTool.java
│               │   │   │   ├── build/
│               │   │   │   │   ├── CompileProjectTool.java
│               │   │   │   │   └── RunTestsTool.java
│               │   │   │   └── plan/
│               │   │   │       ├── CreatePlanTool.java
│               │   │   │       ├── UpdatePlanTool.java
│               │   │   │       └── FinishTaskTool.java
│               │   ├── memory/
│               │   │   ├── MemoryFact.java
│               │   │   └── MemoryStore.java
│               │   └── review/
│               │       ├── PendingChange.java
│               │       ├── DiffLine.java
│               │       ├── DiffHunk.java
│               │       └── DiffComputer.java
│               └── util/
│                   ├── JsonUtils.java
│                   ├── StringUtils.java
│                   └── IOUtils.java
├── build.gradle.kts
└── pom.xml (for Maven compatibility)
```

---

## Module Dependencies

### copilot-base

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "1.9.24"
    `maven-publish`
}

group = "com.kalynx"
version = "0.1.0"

dependencies {
    // Core dependencies (no IDE-specific)
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.annotations:annotations:24.1.0")
    
    testImplementation(kotlin("test"))
}

publishing {
    publications.create<MavenPublication>("maven") {
        groupId = "com.kalynx"
        artifactId = "copilot-base"
        version = "0.1.0"
        
        from(components["java"])
    }
}
```

### copilot-intellij

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "1.9.24"
}

dependencies {
    implementation(project(":copilot-base"))
    
    // IntelliJ dependencies
    implementation("com.intellij:core-api:233.11799.240")
    implementation("com.intellij:ui-designer:*")
    
    testImplementation(kotlin("test"))
}
```

### copilot-eclipse

```kotlin
// build.gradle.kts
plugins {
    id("org.eclipse.tycho") version "3.0.0"
}

dependencies {
    implementation(project(":copilot-base"))
    
    // Eclipse dependencies
    implementation("org.eclipse.core:resources:3.12.0")
    implementation("org.eclipse.jface:text:3.19.0")
    implementation("org.eclipse.jdt:core:3.25.0")
}
```

---

## Package Structure Details

### copilot.core.agent

```java
package copilot.core.agent;

/**
 * Main agent session that drives the agentic loop.
 */
public class AgentSession {
    private final AgentConfig config;
    private final ConversationHistory history;
    private final List<AgentTool> tools;
    
    public AgentSession(AgentConfig config) { ... }
    
    public String chat(String userMessage, AgentCallback callback) { ... }
    
    public void addTool(AgentTool tool) { ... }
    
    public void addContextFile(String path, String content) { ... }
    
    public List<ChatMessage> getHistory() { ... }
}

/**
 * Callback interface for streaming responses.
 */
public interface AgentCallback {
    void onStatus(String message);
    void onThinking(String reasoning);
    void onResponseChunk(String chunk);
    void onToolStart(String toolName, String description);
    void onToolEnd(String toolName, boolean success);
    void onUsage(int promptTokens, int completionTokens, int contextWindow);
}

/**
 * Configuration for the agent.
 */
public class AgentConfig {
    private String model = "gpt-4";
    private int maxIterations = 50;
    private int maxOutputTokens = 8192;
    
    public AgentConfig setModel(String model) { ... }
    public AgentConfig setMaxIterations(int iterations) { ... }
    public AgentConfig setMaxOutputTokens(int tokens) { ... }
}

/**
 * Phase enumeration for the agent workflow.
 */
public enum Phase {
    ANALYSE,      // Initial analysis and understanding
    PLAN,         // Creating resolution plan
    RESOLVE,      // Executing resolution steps
    VERIFY,       // Verifying changes
    DONE          // Task complete
}
```

### copilot.core.chat

```java
package copilot.core.chat;

/**
 * Represents a single chat message.
 */
public class ChatMessage {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
    
    private final Role role;
    private final String content;
    private final List<ToolCall> toolCalls;
    
    public static ChatMessage system(String content) { ... }
    public static ChatMessage user(String content) { ... }
    public static ChatMessage assistant(String content) { ... }
    public static ChatMessage assistantWithToolCalls(JsonArray toolCalls, String narration) { ... }
    public static ChatMessage toolResult(String callId, String toolName, String result) { ... }
    
    // Getters...
}

/**
 * Manages conversation history with pruning and compression.
 */
public class ConversationHistory {
    private final List<ChatMessage> messages = new ArrayList<>();
    private int maxMessages = 60;
    
    public void add(ChatMessage message) { ... }
    public List<ChatMessage> getMessages() { ... }
    public void setSystemMessage(String content) { ... }
    public void pruneToolMessages() { ... }
    public void pruneOldToolPairs(int keepRecentPairs) { ... }
    public void replaceMessages(List<ChatMessage> newMessages) { ... }
}
```

### copilot.core.context

```java
package copilot.core.context;

/**
 * Manages files available to the agent (core logic).
 */
public abstract class ContextManager {
    protected List<WatchedEntry> entries = new ArrayList<>();
    
    public void addEntry(String path, boolean isFolder) { ... }
    public void removeEntry(String path) { ... }
    public List<WatchedEntry> getEntries() { ... }
    
    public String buildContextBlock() { ... }
    public String getFileContent(String path) throws Exception { ... }
    
    // Abstract methods for IDE integration
    protected abstract String readFile(VirtualFile file);
    protected abstract void regenerateSnapshot(String path);
}

/**
 * Entry representing a tracked file or folder.
 */
public static class WatchedEntry {
    private final String relativePath;
    private final boolean isFolder;
    
    public WatchedEntry(String path, boolean isFolder) { ... }
    public String getRelativePath() { ... }
    public boolean isFolder() { ... }
}
```

### copilot.core.tools

```java
package copilot.core.tools;

import com.google.gson.JsonObject;

/**
 * Interface for all agent tools.
 */
public interface AgentTool {
    String getName();
    String getDescription();
    JsonObject getParameterSchema();
    String execute(JsonObject params) throws Exception;
    
    default String getStatusMessage(JsonObject args) { return getName(); }
    default boolean shouldShowResultInChat() { return false; }
}

/**
 * Path validation and security utilities.
 */
public class PathGuard {
    public static String check(String basePath, String userPath) {
        // Validate path doesn't escape project directory
        // Reject .., symlinks, absolute paths outside base
    }
    
    public static Path resolve(String basePath, String userPath) {
        // Safely resolve path within base directory
    }
}

/**
 * Process execution wrapper.
 */
public class ProcessRunner {
    public static Result run(String[] command, File workingDir, int timeoutSeconds) {
        // Execute process with timeout
        // Capture stdout/stderr
        // Return result with exit code and output
    }
    
    public static class Result {
        private final int exitCode;
        private final String stdout;
        private final String stderr;
        
        public boolean timedOut() { ... }
        public String combined() { ... }
    }
}
```

### copilot.core.memory

```java
package copilot.core.memory;

import java.util.List;

/**
 * Represents a single memory fact.
 */
public class MemoryFact {
    private final String id;
    private final String text;
    private final List<String> fileTags;
    private final long timestamp;
    
    public MemoryFact(String id, String text, List<String> fileTags, long timestamp) { ... }
    
    // Getters...
}

/**
 * Stores and retrieves semantic facts.
 */
public class MemoryStore {
    private final List<MemoryFact> facts = new ArrayList<>();
    private int maxFacts = 100;
    
    public void add(String text, List<String> fileTags) { ... }
    public List<MemoryFact> search(String query, int limit) { ... }
    public List<MemoryFact> getAllFacts() { ... }
}
```

### copilot.core.review

```java
package copilot.core.review;

import java.util.List;

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
                         ChangeType type) { ... }
    
    // Getters...
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
    
    // Getters...
}

/**
 * Represents a hunk of changes.
 */
public class DiffHunk {
    private final int headerStart;
    private final int headerEnd;
    private final List<DiffLine> lines;
    
    // Getters...
}

/**
 * Computes diffs between file contents.
 */
public class DiffComputer {
    public static List<DiffHunk> compute(String original, String modified) {
        // Compute line-by-line diff
        // Return list of hunks
    }
}
```

---

## IDE-Specific Extensions

### copilot-intellij

```java
package copilot.intellij.context;

import copilot.core.context.ContextManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

/**
 * IntelliJ-specific context manager with VFS integration.
 */
public class IntelliJContextManager extends ContextManager {
    private final Project project;
    
    public IntelliJContextManager(Project project) {
        this.project = project;
        // Register VFS listeners, etc.
    }
    
    @Override
    protected String readFile(VirtualFile file) {
        return new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
    }
    
    @Override
    protected void regenerateSnapshot(String path) {
        // IntelliJ-specific snapshot regeneration
    }
}
```

### copilot-eclipse

```java
package copilot.eclipse.context;

import copilot.core.context.ContextManager;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

/**
 * Eclipse-specific context manager with JDT integration.
 */
public class EclipseContextManager extends ContextManager {
    private final IProject project;
    
    public EclipseContextManager(IProject project) {
        this.project = project;
        // Register resource listeners, etc.
    }
    
    @Override
    protected String readFile(IFile file) {
        return new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
    }
    
    @Override
    protected void regenerateSnapshot(String path) {
        // Eclipse-specific snapshot regeneration
    }
}
```

---

## Summary

| Module | Purpose | Dependencies |
|--------|---------|--------------|
| `copilot-base` | Core agnostic logic | gson, annotations |
| `copilot-intellij` | IntelliJ integration | copilot-base, IntelliJ API |
| `copilot-eclipse` | Eclipse integration | copilot-base, Eclipse API |

**Key Design Principles:**
1. **Zero IDE dependencies in base** - Pure Java + JSON
2. **Adapter pattern for IDE integration** - Extend abstract classes
3. **Interface-based tool API** - Easy to extend with custom tools
4. **No external build tools required** - Works with Maven/Gradle
