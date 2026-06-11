# IDE Agnostic Decoupling Plan

## Executive Summary

This document outlines the plan to extract IDE-agnostic logic from the Kalynx Copilot IntelliJ plugin into a reusable `copilot-base` library, enabling cross-IDE support (starting with Eclipse).

---

## Current Architecture Analysis

### Project Structure

```
Kalynx-Copilot/
├── copilot/              # IntelliJ-specific implementation
│   └── src/main/java/copilot/
│       ├── agent/        # Agent logic (mostly agnostic)
│       ├── chat/         # UI components (IDE-specific)
│       ├── context/      # Context management (mostly agnostic)
│       ├── review/       # Change review (IDE-specific)
│       └── ui/           # UI components (IDE-specific)
├── tools-api/            # Tool interfaces (agnostic)
└── [maven|gradle]-tools/ # Build tool integrations (agnostic)
```

---

## Phase 1: Identify Already-Agnostic Components

### ✅ Fully Agnostic (No Changes Required)

These components have no IntelliJ dependencies and can be moved to `copilot-base` immediately:

| Component | Location | Description |
|-----------|----------|-------------|
| `AgentTool` interface | `tools-api/src/main/java/copilot/tools/api/AgentTool.java` | Tool interface with JSON params |
| `PathGuard` | `tools-api/src/main/java/copilot/tools/api/PathGuard.java` | Path validation utility |
| `ProcessRunner` | `tools-api/src/main/java/copilot/tools/api/ProcessRunner.java` | Process execution wrapper |
| `AgentSession` | `copilot/src/main/java/copilot/agent/AgentSession.java` | Core agentic loop logic |
| `ConversationHistory` | `copilot/src/main/java/copilot/chat/ConversationHistory.java` | Message history management |
| `ChatMessage` | `copilot/src/main/java/copilot/chat/ChatMessage.java` | Message model |
| `AgentCallback` | `copilot/src/main/java/copilot/agent/AgentCallback.java` | Callback interface |
| `Phase` / `PhaseController` | `copilot/src/main/java/copilot/agent/` | Phase state management |
| All tool implementations | `copilot/src/main/java/copilot/agent/tools/` | File operations, search, etc. |
| `ContextManager` (core logic) | `copilot/src/main/java/copilot/context/ContextManager.java` | File tracking (excluding UI) |
| `StrippedGenerator` | `copilot/src/main/java/copilot/context/StrippedGenerator.java` | Code stripping utility |
| `LanguageStripper` / `BraceLanguageStripper` / `PythonStripper` | `copilot/src/main/java/copilot/context/` | File content processors |
| `MemoryFact` / `MemoryStore` | `copilot/src/main/java/copilot/memory/` | Semantic memory components |
| `PendingChange` / `Diff*` | `copilot/src/main/java/copilot/review/` | Change tracking models |
| `MavenBuildTool` | `maven-tools/src/main/java/copilot/maven/MavenBuildTool.java` | Maven integration |
| `GradleBuildTool` | `gradle-tools/src/main/java/copilot/gradle/GradleBuildTool.java` | Gradle integration |

### ⚠️ Partially Agnostic (Requires Refactoring)

These components have mixed dependencies:

| Component | Current Location | IntelliJ Dependencies | Action Required |
|-----------|------------------|----------------------|-----------------|
| `ContextManager` | `copilot/src/main/java/copilot/context/` | `PersistentStateComponent`, VFS listeners | Extract core logic; keep IDE integration separate |
| `ChangeReviewManager` | `copilot/src/main/java/copilot/review/` | IntelliJ UI components | Move to `copilot-ui` module |
| `CopilotChatPanel` | `copilot/src/main/java/copilot/chat/` | IntelliJ UI, ToolWindow API | Move to `copilot-intellij` module |

---

## Phase 2: Define New Module Structure

### Proposed Module Hierarchy

```
copilot-base/              # Core agnostic logic (NEW)
├── src/main/java/
│   ├── copilot/core/      # Agent, chat, conversation
│   │   ├── agent/
│   │   │   ├── AgentSession.java
│   │   │   ├── AgentCallback.java
│   │   │   ├── Phase.java
│   │   │   └── PhaseController.java
│   │   ├── chat/
│   │   │   ├── ChatMessage.java
│   │   │   ├── ConversationHistory.java
│   │   │   └── ChatMode.java
│   │   └── context/
│   │       ├── ContextManager.java
│   │       ├── StrippedGenerator.java
│   │       └── LanguageStripper.java
│   ├── copilot/tools/     # Tool implementations
│   │   ├── api/
│   │   │   ├── AgentTool.java
│   │   │   ├── PathGuard.java
│   │   │   └── ProcessRunner.java
│   │   └── impl/
│   │       ├── file/
│   │       │   ├── GetCurrentFileTool.java
│   │       │   ├── ListFilesTool.java
│   │       │   ├── ReadFileTool.java
│   │       │   ├── ReplaceInFileTool.java
│   │       │   ├── CreateFileTool.java
│   │       │   └── ...
│   │       ├── search/
│   │       │   ├── SearchInFilesTool.java
│   │       │   └── ScanProblemsTool.java
│   │       ├── build/
│   │       │   ├── CompileProjectTool.java
│   │       │   ├── RunTestsTool.java
│   │       │   └── ...
│   │       └── plan/
│   │           ├── CreatePlanTool.java
│   │           ├── UpdatePlanTool.java
│   │           └── FinishTaskTool.java
│   └── copilot/memory/    # Semantic memory
│       ├── MemoryFact.java
│       └── MemoryStore.java

copilot-intellij/          # IntelliJ-specific integration (NEW)
├── src/main/java/
│   └── copilot/intellij/
│       ├── ui/
│       │   ├── CopilotChatPanel.java
│       │   ├── AgentPlanPanel.java
│       │   ├── ContextPanel.java
│       │   └── ReviewPanel.java
│       ├── chat/
│       │   ├── CopilotChatToolWindowFactory.java
│       │   └── SectionEditorPanel.java
│       └── context/
│           └── IntelliJContextManager.java  # IDE-specific extension

copilot-eclipse/           # Eclipse-specific integration (NEW)
├── src/main/java/
│   └── copilot/eclipse/
│       ├── ui/
│       │   ├── CopilotChatView.java
│       │   └── ...
│       └── context/
│           └── EclipseContextManager.java

copilot-common-ui/         # Shared UI components (NEW)
├── src/main/java/
│   └── copilot/ui/
│       ├── LoadingButton.java
│       └── ...            # Reusable UI primitives
```

---

## Phase 3: Refactoring Tasks by Effort

### 🔴 HIGH PRIORITY - Minimal Effort (1-2 days each)

These changes require minimal code modification:

| Task | Files Affected | Effort | Impact |
|------|----------------|--------|--------|
| **1. Extract core agent logic** | `AgentSession.java`, `ConversationHistory.java` | Low | Foundation for all other work |
| **2. Move tool interfaces** | `tools-api/` entirely | Low | Enables tool reuse across IDEs |
| **3. Separate context management** | `ContextManager.java` (core methods) | Medium | Allows different storage backends |
| **4. Extract memory components** | `MemoryFact.java`, `MemoryStore.java` | Low | Reusable semantic memory |
| **5. Create build tool abstractions** | `MavenBuildTool.java`, `GradleBuildTool.java` | Low | Cross-platform build support |

### 🟡 MEDIUM PRIORITY - Moderate Effort (3-5 days each)

These require more significant restructuring:

| Task | Files Affected | Effort | Impact |
|------|----------------|--------|--------|
| **6. Refactor ContextManager** | `ContextManager.java` + VFS integration | High | Clean separation of concerns |
| **7. Create IntelliJ adapter** | New `copilot-intellij` module | Medium | Maintains current functionality |
| **8. Extract UI components** | `CopilotChatPanel.java`, etc. | Medium | Enables Eclipse UI implementation |
| **9. Implement Eclipse integration** | New `copilot-eclipse` module | High | First cross-IDE target |

### 🟢 LOW PRIORITY - Higher Effort (1+ week each)

Strategic improvements:

| Task | Files Affected | Effort | Impact |
|------|----------------|--------|--------|
| **10. Document public API** | All `copilot-base` modules | Medium | Essential for external consumers |
| **11. Add integration tests** | New test modules | High | Confidence in refactoring |
| **12. Create example implementations** | Documentation + samples | Medium | Accelerates adoption |

---

## Detailed Implementation Plan

### Step 1: Create `copilot-base` Module Structure

```kotlin
// settings.gradle.kts - Add new module
include("copilot-base")
project(":copilot-base").projectDir = file("copilot-base")

// copilot-base/build.gradle.kts
plugins {
    kotlin("jvm") version "1.9.24"
}

dependencies {
    // Only agnostic dependencies
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.annotations:annotations:24.1.0")
}
```

### Step 2: Move and Refactor Core Classes

```java
// Before: copilot/src/main/java/copilot/agent/AgentSession.java
package copilot.agent;

// After: copilot-base/src/main/java/copilot/core/agent/AgentSession.java
package copilot.core.agent;

// Remove IntelliJ imports:
// import com.intellij.openapi.project.Project;  // Move to adapter
// import com.intellij.openapi.vfs.VirtualFile;   // Move to adapter

public class AgentSession {
    // Keep: Core logic unchanged
    // Change: Project parameter becomes Context interface
}
```

### Step 3: Create IDE Adapter Layer

```java
// copilot-intellij/src/main/java/copilot/intellij/context/IntelliJContextManager.java
package copilot.intellij.context;

import copilot.core.context.ContextManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

public class IntelliJContextManager extends ContextManager {
    private final Project project;
    
    public IntelliJContextManager(Project project) {
        this.project = project;
    }
    
    @Override
    protected void onFileChanged(String path) {
        // IntelliJ-specific VFS handling
    }
    
    @Override
    protected String readFile(VirtualFile file) {
        // Use IntelliJ VFS API
    }
}
```

### Step 4: Update Dependencies

```kotlin
// copilot/build.gradle.kts - Original plugin module
dependencies {
    implementation(project(":copilot-base"))
    implementation(project(":tools-api"))
    
    // Keep IntelliJ-specific deps here
    implementation("com.intellij:core-api:233.11799.240")
}
```

---

## Risk Assessment

| Risk | Mitigation |
|------|------------|
| Breaking existing functionality | Maintain backward compatibility; use adapter pattern |
| Loss of IntelliJ-specific features | Keep `copilot-intellij` module with full feature parity |
| Increased complexity | Clear module boundaries; well-defined APIs |
| Testing coverage reduction | Add comprehensive integration tests for each module |

---

## Success Criteria

- [ ] All core agent logic compiles without IntelliJ dependencies
- [ ] `copilot-base` can be consumed as a standalone library
- [ ] IntelliJ plugin continues to work with no functional regression
- [ ] Eclipse integration can be built using `copilot-base`
- [ ] Documentation exists for extending `copilot-base` to new IDEs

---

## Timeline Estimate

| Phase | Duration |
|-------|----------|
| Phase 1: Analysis & Planning | 2 days |
| Phase 2: Core Extraction | 5 days |
| Phase 3: IntelliJ Adapter | 3 days |
| Phase 4: Eclipse Integration | 5 days |
| Phase 5: Testing & Documentation | 3 days |
| **Total** | **18 days (~3.5 weeks)** |

---

## Next Steps

1. ✅ Create `copilot-base` module structure
2. ✅ Move core agent classes (`AgentSession`, `ConversationHistory`)
3. ✅ Extract tool interfaces to `tools-api`
4. ✅ Create IntelliJ adapter for VFS/context management
5. ✅ Begin Eclipse integration work

---

*This plan will be updated as implementation progresses and new discoveries are made.*
