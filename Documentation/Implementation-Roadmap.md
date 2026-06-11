# Implementation Roadmap

## Timeline: 4 Weeks

---

## Week 1: Foundation & Core Extraction

### Day 1-2: Project Setup & Analysis

**Goals:**
- Create `copilot-base` module structure
- Finalize agnostic component inventory
- Establish versioning strategy

**Tasks:**

```bash
# Create new module structure
mkdir -p copilot-base/src/main/java/copilot/core/{agent,chat,context,tools,memory,review}
mkdir -p copilot-intellij/src/main/java/copilot/intellij/{ui,context}

# Update settings.gradle.kts
include("copilot-base")
include("copilot-intellij")

# Create base module build configuration
cat > copilot-base/build.gradle.kts << 'EOF'
plugins {
    kotlin("jvm") version "1.9.24"
    `maven-publish`
}

group = "com.kalynx"
version = "0.1.0"

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.annotations:annotations:24.1.0")
}

publishing {
    publications.create<MavenPublication>("maven") {
        groupId = "com.kalynx"
        artifactId = "copilot-base"
        version = "0.1.0"
        
        from(components["java"])
    }
}
EOF
```

**Deliverable:** Module structure ready, inventory confirmed

---

### Day 3-4: Core Agent Classes Migration

**Goals:**
- Move `AgentSession`, `ChatMessage`, `ConversationHistory`
- Update all imports in `copilot` module
- Verify compilation

**Migration Script (conceptual):**

```python
# migration_script.py
import os
import shutil

# Files to move to copilot-base
agnostic_files = [
    ("copilot/src/main/java/copilot/agent/AgentSession.java", 
     "copilot-base/src/main/java/copilot/core/agent/AgentSession.java"),
    ("copilot/src/main/java/copilot/agent/AgentCallback.java",
     "copilot-base/src/main/java/copilot/core/agent/AgentCallback.java"),
    ("copilot/src/main/java/copilot/chat/ChatMessage.java",
     "copilot-base/src/main/java/copilot/core/chat/ChatMessage.java"),
    ("copilot/src/main/java/copilot/chat/ConversationHistory.java",
     "copilot-base/src/main/java/copilot/core/chat/ConversationHistory.java"),
    # ... more files
]

for src, dst in agnostic_files:
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copy(src, dst)
    
    # Update package declaration
    with open(dst, 'r') as f:
        content = f.read()
    
    content = content.replace(
        "package copilot.agent;",
        "package copilot.core.agent;"
    )
    content = content.replace(
        "package copilot.chat;",
        "package copilot.core.chat;"
    )
    
    with open(dst, 'w') as f:
        f.write(content)

print("Migration complete!")
```

**Deliverable:** Core classes moved, imports updated

---

### Day 5: Tool Interface Migration

**Goals:**
- Move `tools-api` to `copilot-base`
- Verify all tool implementations compile
- Test basic tool execution

**Tasks:**

```java
// Before:
package copilot.tools.api;

public interface AgentTool { ... }

// After:
package copilot.core.tools;

public interface AgentTool { ... }
```

**Verification Checklist:**
- [ ] All 20+ tool classes import correctly
- [ ] No IntelliJ dependencies in tool implementations
- [ ] Basic test harness passes

---

## Week 2: Context Management Refactoring

### Day 6-7: ContextManager Split

**Goals:**
- Extract core `ContextManager` logic
- Create IDE adapter interface
- Implement `IntelliJContextManager`

**Refactoring Approach:**

```java
// copilot-base/src/main/java/copilot/core/context/ContextManager.java
package copilot.core.context;

public abstract class ContextManager {
    // Core logic - no IntelliJ dependencies
    protected List<WatchedEntry> entries = new ArrayList<>();
    
    public void addEntry(String path, boolean isFolder) { ... }
    public void removeEntry(String path) { ... }
    public String buildContextBlock() { ... }
    
    // Abstract methods for IDE integration
    protected abstract String readFile(VirtualFile file);
    protected abstract void regenerateSnapshot(String path);
}

// copilot-intellij/src/main/java/copilot/intellij/context/IntelliJContextManager.java
package copilot.intellij.context;

public class IntelliJContextManager extends ContextManager {
    private final Project project;
    
    @Override
    protected String readFile(VirtualFile file) {
        // Use IntelliJ VFS
        return new String(file.contentsToByteArray(), UTF_8);
    }
    
    @Override
    protected void regenerateSnapshot(String path) {
        // IntelliJ-specific snapshot regeneration
    }
}
```

**Deliverable:** ContextManager split, IntelliJ adapter working

---

### Day 8-9: UI Component Migration

**Goals:**
- Identify which UI components can be shared
- Move to `copilot-common-ui` or keep in `copilot-intellij`
- Create Eclipse UI stubs

**Decision Matrix:**

| Component | Decision | Reason |
|-----------|----------|--------|
| `CopilotChatPanel` | Stay in `copilot-intellij` | ToolWindow API specific |
| `AgentPlanPanel` | Move to `copilot-common-ui` | Pure Swing/JavaFX |
| `LoadingButton` | Move to `copilot-common-ui` | Reusable component |
| `SectionEditorPanel` | Stay in `copilot-intellij` | Editor integration |

**Deliverable:** UI components properly categorized

---

### Day 10: Build Tool Integration

**Goals:**
- Verify Maven/Gradle tools work standalone
- Create unified build tool interface

```java
// copilot-base/src/main/java/copilot/core/build/BuildTool.java
package copilot.core.build;

public interface BuildTool {
    String getName();
    boolean canBuild(Path projectDir);
    BuildResult build(Path projectDir, BuildOptions options) throws Exception;
}

// copilot-base/src/main/java/copilot/core/build/MavenBuildTool.java
public class MavenBuildTool implements BuildTool {
    @Override
    public boolean canBuild(Path projectDir) {
        return Files.exists(projectDir.resolve("pom.xml"));
    }
    
    @Override
    public BuildResult build(Path projectDir, BuildOptions options) {
        // Run mvn command
    }
}

// copilot-base/src/main/java/copilot/core/build/GradleBuildTool.java
public class GradleBuildTool implements BuildTool {
    @Override
    public boolean canBuild(Path projectDir) {
        return Files.exists(projectDir.resolve("build.gradle"))
            || Files.exists(projectDir.resolve("build.gradle.kts"));
    }
    
    @Override
    public BuildResult build(Path projectDir, BuildOptions options) {
        // Run gradle command
    }
}
```

**Deliverable:** Unified build tool interface

---

## Week 3: Eclipse Integration

### Day 11-12: Eclipse Module Setup

**Goals:**
- Create `copilot-eclipse` module structure
- Set up Eclipse plugin project
- Configure dependencies

```bash
# Create Eclipse plugin structure
mkdir -p copilot-eclipse/src/main/java/copilot/eclipse/{ui,context}
mkdir -p copilot-eclipse/plugin.xml

# Update settings.gradle.kts
include("copilot-eclipse")

# Create Eclipse-specific build (using Tycho or standard Gradle)
cat > copilot-eclipse/build.gradle.kts << 'EOF'
plugins {
    id("org.eclipse.tycho") version "3.0.0"
}

dependencies {
    implementation(project(":copilot-base"))
    implementation("org.eclipse.core:resources:3.12.0")
    implementation("org.eclipse.jface:text:3.19.0")
}
EOF
```

**Deliverable:** Eclipse module structure ready

---

### Day 13-14: Eclipse Context Manager

**Goals:**
- Implement `EclipseContextManager`
- Integrate with JDT (Java Development Tools)
- Handle resource change events

```java
// copilot-eclipse/src/main/java/copilot/eclipse/context/EclipseContextManager.java
package copilot.eclipse.context;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeListener;

public class EclipseContextManager extends ContextManager {
    private final IProject project;
    private IResourceChangeListener listener;
    
    public EclipseContextManager(IProject project) {
        this.project = project;
        
        // Register listener for JDT resource changes
        listener = event -> {
            // Handle file changes from JDT
            for (IResourceDelta delta : event.getAffectedChildren()) {
                if (delta.getResource() instanceof IFile) {
                    // Process changed files
                }
            }
        };
        
        project.getWorkspace().addResourceChangeListener(listener);
    }
    
    @Override
    protected String readFile(IFile file) {
        // Use JDT API
        return new String(file.contentsToByteArray(), UTF_8);
    }
    
    @Override
    public void dispose() {
        project.getWorkspace().removeResourceChangeListener(listener);
    }
}
```

**Deliverable:** Eclipse context manager functional

---

### Day 15-16: Eclipse UI Implementation

**Goals:**
- Implement Eclipse view for chat
- Create toolbar contributions
- Integrate with Eclipse editor

```java
// copilot-eclipse/src/main/java/copilot/eclipse/ui/CopilotChatView.java
package copilot.eclipse.ui;

import org.eclipse.swt.widgets.Composite;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.core.resources.IResource;

public class CopilotChatView extends ViewPart {
    private TreeViewer treeViewer;
    
    @Override
    public void createPartControl(Composite parent) {
        treeViewer = new TreeViewer(parent);
        
        // Set up content provider, label provider
        treeViewer.setContentProvider(new ChatContentProvider());
        treeViewer.setLabelProvider(new ChatLabelProvider());
        
        // Load chat history
        treeViewer.setInput(chatManager.getHistory());
    }
    
    @Override
    public void setFocus() {
        treeViewer.getControl().setFocus();
    }
}
```

**Deliverable:** Basic Eclipse UI functional

---

### Day 17: Cross-IDE Testing

**Goals:**
- Test both IntelliJ and Eclipse implementations
- Verify feature parity
- Document differences

**Test Matrix:**

| Feature | IntelliJ | Eclipse | Notes |
|---------|----------|---------|-------|
| Agent loop | ✅ | ✅ | Same core logic |
| Tool calling | ✅ | ✅ | Same tools |
| Context management | ✅ | ✅ | IDE-specific impl |
| Chat UI | ✅ | ⚠️ | Basic implementation |
| Build tools | ✅ | ✅ | Same Maven/Gradle |

**Deliverable:** Test report, known limitations documented

---

## Week 4: Stabilization & Documentation

### Day 18-19: API Documentation

**Goals:**
- Document public APIs
- Create usage examples
- Write migration guide

**Documentation Structure:**

```
copilot-base/docs/
├── api/
│   ├── AgentSession.md
│   ├── AgentTool.md
│   ├── ContextManager.md
│   └── ConversationHistory.md
├── guides/
│   ├── getting-started.md
│   ├── implementing-tools.md
│   └── extending-to-new-ides.md
└── examples/
    ├── basic-usage.java
    ├── custom-tool.java
    └── ide-integration.java
```

**Example Documentation:**

```markdown
# AgentSession API

The `AgentSession` class is the main entry point for using the copilot agent.

## Basic Usage

```java
import copilot.core.agent.AgentSession;
import copilot.core.agent.AgentConfig;

// Create configuration
AgentConfig config = new AgentConfig()
    .setModel("gpt-4")
    .setMaxIterations(50);

// Create session
AgentSession session = new AgentSession(config);

// Run agent
String response = session.chat("Your prompt here", callback);
```

## Configuration Options

| Option | Default | Description |
|--------|---------|-------------|
| `model` | `"gpt-4"` | Model identifier |
| `maxIterations` | `50` | Maximum agent loop iterations |
| `maxOutputTokens` | `8192` | Maximum output tokens |

## Thread Safety

`AgentSession` is **not thread-safe**. Use a single instance per conversation or synchronize access.
```

**Deliverable:** Complete API documentation

---

### Day 20: Integration Tests

**Goals:**
- Write integration tests for all modules
- Verify end-to-end functionality
- Set up CI/CD pipeline

```kotlin
// copilot-base/src/test/kotlin/copilot/core/agent/AgentSessionIntegrationTest.kt
class AgentSessionIntegrationTest {
    
    @Test
    fun `end-to-end agent session with tools`() {
        val config = AgentConfig()
            .setModel("gpt-4")
            .setMaxIterations(10)
        
        val session = AgentSession(config)
        
        // Add a tool
        session.addTool(object : AgentTool {
            override fun getName() = "test_tool"
            override fun getDescription() = "Test tool"
            override fun execute(params: JsonObject) = "Test result"
        })
        
        // Run agent
        val response = session.chat("Use test_tool", mockCallback())
        
        // Verify response contains expected content
        assertTrue(response.contains("Test result"))
    }
    
    @Test
    fun `context management works correctly`() {
        val manager = ContextManager()
        
        manager.addEntry("test.java", false)
        manager.addContextFileContent("test.java", "public class Test {}")
        
        val context = manager.buildContextBlock()
        
        assertTrue(context.contains("test.java"))
        assertTrue(context.contains("public class Test {}"))
    }
}
```

**Deliverable:** Integration test suite passing

---

### Day 21: Versioning & Release

**Goals:**
- Set up semantic versioning
- Create release pipeline
- Document breaking changes

```yaml
# .github/workflows/release.yml
name: Release copilot-base

on:
  push:
    tags:
      - 'v*'

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: 17
          distribution: temurin
      
      - name: Build and publish
        run: |
          ./gradlew build publishToMavenLocal
        env:
          MAVEN_USERNAME: ${{ secrets.MAVEN_USERNAME }}
          MAVEN_PASSWORD: ${{ secrets.MAVEN_PASSWORD }}
```

**Deliverable:** Release pipeline configured

---

### Day 22-23: Migration Guide for Existing Users

**Goals:**
- Document breaking changes
- Provide migration path
- Maintain backward compatibility where possible

**Migration Guide Outline:**

```markdown
# Migration Guide: copilot → copilot-base

## Overview

The copilot functionality has been reorganized into a modular structure:

```
Old Structure                    New Structure
─────────────                    ─────────────
copilot/                         copilot-base/
  ├── agent/        →            core/agent/
  ├── chat/         →            core/chat/
  ├── context/      →            core/context/
  └── tools/        →            core/tools/

copilot-intellij/                (new)
  ├── ui/
  └── context/

copilot-eclipse/                 (new)
  ├── ui/
  └── context/
```

## Breaking Changes

### Package Name Changes

| Old Package | New Package |
|-------------|-------------|
| `copilot.agent` | `copilot.core.agent` |
| `copilot.chat` | `copilot.core.chat` |
| `copilot.context` | `copilot.core.context` |
| `copilot.tools.api` | `copilot.core.tools` |

### API Changes

**AgentSession constructor:**
```java
// Before:
AgentSession session = new AgentSession(project);

// After:
AgentConfig config = new AgentConfig();
AgentSession session = new AgentSession(config);
```

## Migration Steps

1. Update imports to new package names
2. Add `copilot-base` dependency to your project
3. For IntelliJ: add `copilot-intellij` dependency
4. For Eclipse: add `copilot-eclipse` dependency
5. Update any custom tool implementations

## Backward Compatibility

- **No breaking changes** for users of stable APIs
- Deprecated classes in `copilot` module will redirect to new locations
- Version 0.x may have breaking changes; 1.x will maintain compatibility
```

**Deliverable:** Complete migration guide

---

### Day 24: Final Review & Polish

**Goals:**
- Code review of all changes
- Performance optimization
- Security audit

**Review Checklist:**

| Item | Status |
|------|--------|
| All IntelliJ dependencies removed from base | ✅ |
| Public API is well-documented | ✅ |
| Tests cover critical paths | ✅ |
| No security vulnerabilities (path traversal, etc.) | ✅ |
| Thread safety documented | ✅ |
| Error handling comprehensive | ✅ |

**Deliverable:** Final release candidate

---

## Summary Timeline

| Week | Focus | Deliverable |
|------|-------|-------------|
| 1 | Foundation & Core | `copilot-base` module with core classes |
| 2 | Context & Tools | ContextManager split, build tools unified |
| 3 | Eclipse Integration | Full Eclipse support |
| 4 | Documentation & Release | API docs, tests, release |

**Total: 4 weeks**

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Breaking existing functionality | Maintain `copilot` module with deprecation warnings; provide migration path |
| Incomplete Eclipse integration | Focus on core functionality first; UI can be iterative |
| Performance regression | Profile before/after; optimize hot paths |
| Documentation lag | Write docs alongside code; use examples |

---

## Success Metrics

- [ ] 100% of core agent logic in `copilot-base`
- [ ] IntelliJ plugin works identically to pre-refactor
- [ ] Eclipse integration supports basic chat and tools
- [ ] All tests passing (target: >80% coverage)
- [ ] Documentation complete and accurate

---

*This roadmap is subject to adjustment based on discovered complexities during implementation.*
