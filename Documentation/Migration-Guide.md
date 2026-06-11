# Copilot-Base Migration Guide

## Overview

This guide helps you migrate from the current IntelliJ-only copilot implementation to the modular `copilot-base` architecture.

---

## Architecture Changes

### Before (Monolithic)
```
copilot/
├── agent/           # Agent logic
├── chat/            # Chat UI
├── context/         # Context management
├── tools/           # Tool implementations
└── review/          # Change review
    └── All IntelliJ-dependent
```

### After (Modular)
```
copilot-base/              # Core agnostic logic
├── core/
│   ├── agent/           # AgentSession, PhaseController
│   ├── chat/            # ChatMessage, ConversationHistory
│   ├── context/         # ContextManager (core only)
│   ├── tools/           # All tool implementations
│   └── memory/          # Semantic memory
└── util/

copilot-intellij/          # IntelliJ integration
├── ui/                  # IntelliJ-specific UI
└── context/             # IntelliJContextManager

copilot-eclipse/           # Eclipse integration
├── ui/
└── context/
```

---

## Package Name Changes

| Old Package | New Package |
|-------------|-------------|
| `copilot.agent` | `copilot.core.agent` |
| `copilot.chat` | `copilot.core.chat` |
| `copilot.context` | `copilot.core.context` |
| `copilot.tools.api` | `copilot.core.tools` |
| `copilot.memory` | `copilot.core.memory` |
| `copilot.review` | `copilot.core.review` |

---

## Migration Steps

### Step 1: Update Dependencies

**For copilot-base users:**
```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.kalynx</groupId>
    <artifactId>copilot-base</artifactId>
    <version>0.1.0</version>
</dependency>
```

**For IntelliJ plugin:**
```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":copilot-base"))
    implementation("com.intellij:core-api:233.11799.240")
}
```

---

### Step 2: Update Imports

Replace all imports:

```java
// BEFORE
import copilot.agent.AgentSession;
import copilot.chat.ChatMessage;
import copilot.context.ContextManager;

// AFTER
import copilot.core.agent.AgentSession;
import copilot.core.chat.ChatMessage;
import copilot.core.context.ContextManager;
```

---

### Step 3: Update AgentSession Constructor

```java
// BEFORE
AgentSession session = new AgentSession(project);

// AFTER
AgentConfig config = new AgentConfig()
    .setModel("gpt-4")
    .setMaxIterations(50);
    
AgentSession session = new AgentSession(config);
```

---

### Step 4: Update ContextManager Usage

```java
// BEFORE (IntelliJ-specific)
ContextManager cm = ContextManager.getInstance(project);

// AFTER (base module - no project needed)
ContextManager cm = new ContextManager();
cm.addEntry("src/main/Foo.java", false);
cm.setFileContent("src/main/Foo.java", fileContent);
```

---

### Step 5: Update Tool Execution

```java
// BEFORE
String result = tool.execute(params, project);

// AFTER
String result = tool.execute(params);  // No project parameter
```

---

## Breaking Changes

### 1. AgentSession Constructor

**Change:** No longer takes `Project` parameter.

**Migration:**
```java
// Old
AgentSession session = new AgentSession(project);

// New
AgentConfig config = new AgentConfig();
AgentSession session = new AgentSession(config);
```

---

### 2. ContextManager

**Change:** Abstract class now; requires IDE-specific implementation.

**Migration:**

For **IntelliJ**:
```java
import copilot.intellij.context.IntelliJContextManager;

// Use the IntelliJ-specific implementation
IntelliJContextManager cm = new IntelliJContextManager(project);
```

For **Eclipse**:
```java
import copilot.eclipse.context.EclipseContextManager;

// Use the Eclipse-specific implementation  
EclipseContextManager cm = new EclipseContextManager(project);
```

---

### 3. Tool execute() Method

**Change:** Removed `Project` parameter.

**Migration:**
```java
// Old
tool.execute(params, project);

// New
tool.execute(params);
```

If your tool needs project access:
- Pass required data as parameters
- Or use a context object passed during construction

---

### 4. PathGuard

**Change:** Static methods now throw exceptions instead of returning null.

**Migration:**
```java
// Old
Path path = PathGuard.resolve(base, userPath);
if (path == null) { /* handle error */ }

// New
try {
    Path path = PathGuard.resolve(base, userPath);
} catch (SecurityException e) {
    // Handle error
}
```

---

## Non-Breaking Changes

These changes are backward compatible or have automatic migration:

### 1. ChatMessage Factory Methods

New factory methods added; existing constructors still work:
```java
// Both styles work:
ChatMessage msg = new ChatMessage(Role.USER, "Hello");
ChatMessage msg = ChatMessage.user("Hello");  // New preferred style
```

---

### 2. ConversationHistory

Methods renamed for clarity:
| Old Method | New Method |
|------------|------------|
| `getMessages()` | Same (unchanged) |
| `setSystemMessage()` | Same (unchanged) |
| `pruneToolMessages()` | Same (unchanged) |

---

## IDE-Specific Migration

### IntelliJ Plugin

```kotlin
// settings.gradle.kts
include("copilot-base")
include("copilot-intellij")

// build.gradle.kts for copilot-intellij
dependencies {
    implementation(project(":copilot-base"))
    implementation("com.intellij:core-api:233.11799.240")
}
```

```java
// Use IntelliJ-specific context manager
import copilot.intellij.context.IntelliJContextManager;

IntelliJContextManager cm = new IntelliJContextManager(project);
cm.addEntry(vf.getPath(), vf.isDirectory());
```

---

### Eclipse Plugin

```kotlin
// settings.gradle.kts
include("copilot-base")
include("copilot-eclipse")

// build.gradle.kts for copilot-eclipse
dependencies {
    implementation(project(":copilot-base"))
    implementation("org.eclipse.core:resources:3.12.0")
}
```

```java
// Use Eclipse-specific context manager
import copilot.eclipse.context.EclipseContextManager;

EclipseContextManager cm = new EclipseContextManager(project);
cm.addEntry(file.getProjectRelativePath().toString(), false);
```

---

## Testing the Migration

### Unit Tests

```java
class AgentSessionTest {
    
    @Test
    void testChatWithTool() {
        // Arrange
        AgentConfig config = new AgentConfig();
        AgentSession session = new AgentSession(config);
        
        session.addTool(new TestTool());
        
        // Act
        String response = session.chat("Use test tool", mockCallback());
        
        // Assert
        assertNotNull(response);
    }
    
    @Test
    void testContextManagement() {
        ContextManager cm = new ContextManager();
        cm.addEntry("test.java", false);
        cm.setFileContent("test.java", "public class Test {}");
        
        String context = cm.buildContextBlock();
        
        assertTrue(context.contains("test.java"));
        assertTrue(context.contains("public class Test {}"));
    }
}
```

### Integration Tests

```kotlin
// copilot-intellij/src/test/kotlin/copilot/intellij/IntegrationTest.kt
class IntelliJIntegrationTest {
    
    @Test
    fun `agent session works with IntelliJ context manager`() {
        val config = AgentConfig()
        val session = AgentSession(config)
        
        // Use IntelliJ-specific context manager
        val cm = IntelliJContextManager(testProject)
        session.setContextManager(cm)
        
        val response = session.chat("Test prompt", mockCallback())
        
        assertNotNull(response)
    }
}
```

---

## Rollback Plan

If issues arise, you can rollback:

```bash
# Keep both versions temporarily
git checkout -b feature/copilot-base
git checkout main

# Revert changes when ready
git revert <commit-hash>
```

---

## Support Resources

- **Documentation:** See `API-Reference.md` for full API docs
- **Examples:** See `examples/` directory for sample code
- **Issues:** Report bugs in the main repository issue tracker

---

## Timeline

| Week | Task |
|------|------|
| 1 | Update imports, fix compilation errors |
| 2 | Update AgentSession usage, test core functionality |
| 3 | Integrate IDE-specific context managers |
| 4 | Test end-to-end with sample projects |

---

*This guide will be updated as the migration progresses.*
