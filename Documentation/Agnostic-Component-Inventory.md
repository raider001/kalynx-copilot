# Agnostic Component Inventory

## Date: 2024-01-15
## Purpose: Identify components that can be moved to copilot-base

---

## ✅ CATEGORY 1: Fully Agnostic (Move Immediately)

These components have **zero IntelliJ dependencies** and can be moved to `copilot-base` without any changes.

### Core Agent Logic

| File | Location | Description |
|------|----------|-------------|
| `AgentSession.java` | `copilot/src/main/java/copilot/agent/` | Main agentic loop, tool calling orchestration |
| `AgentCallback.java` | `copilot/src/main/java/copilot/agent/` | Callback interface for streaming responses |
| `Phase.java` | `copilot/src/main/java/copilot/agent/` | Phase enumeration (ANALYSE, PLAN, RESOLVE) |
| `PhaseController.java` | `copilot/src/main/java/copilot/agent/` | Phase state management and transitions |

### Chat & Conversation

| File | Location | Description |
|------|----------|-------------|
| `ChatMessage.java` | `copilot/src/main/java/copilot/chat/` | Message model with role, content, tool calls |
| `ConversationHistory.java` | `copilot/src/main/java/copilot/chat/` | Ordered message list with pruning/compression |
| `ChatMode.java` | `copilot/src/main/java/copilot/chat/` | Chat mode configuration |

### Tools API

| File | Location | Description |
|------|----------|-------------|
| `AgentTool.java` | `tools-api/src/main/java/copilot/tools/api/` | Tool interface definition |
| `PathGuard.java` | `tools-api/src/main/java/copilot/tools/api/` | Path validation and security |
| `ProcessRunner.java` | `tools-api/src/main/java/copilot/tools/api/` | Process execution wrapper |

### Tool Implementations (All in `copilot/src/main/java/copilot/agent/tools/`)

| File | Purpose |
|------|---------|
| `GetCurrentFileTool.java` | Get active editor file |
| `ListFilesTool.java` | List project directory contents |
| `AddToContextTool.java` | Pin files to dynamic context |
| `RemoveFromContextTool.java` | Unpin files from context |
| `ListContextTool.java` | List currently pinned files |
| `ClearContextTool.java` | Clear all pinned files |
| `ReadFileTool.java` | Read and pin file content |
| `ReplaceInFileTool.java` | Replace code in file |
| `CreateFileTool.java` | Create new file |
| `SearchInFilesTool.java` | Search text across project |
| `ScanProblemsTool.java` | IntelliJ inspections scan |
| `GetIDEProblemsTool.java` | Get IDE errors/warnings |
| `CompileProjectTool.java` | Compile project |
| `RunTestsTool.java` | Run Maven/Gradle tests |
| `CreatePlanTool.java` | Create resolution plan |
| `UpdatePlanTool.java` | Update plan milestones |
| `FinishTaskTool.java` | Mark task complete |
| `CompletePhaseTool.java` | Signal phase completion |
| `RememberTool.java` | Store durable facts |

### Context Management (Partial)

| File | Location | Description |
|------|----------|-------------|
| `StrippedGenerator.java` | `copilot/src/main/java/copilot/context/` | Generate stripped file snapshots |
| `LanguageStripper.java` | `copilot/src/main/java/copilot/context/` | Interface for language-specific stripping |
| `BraceLanguageStripper.java` | `copilot/src/main/java/copilot/context/` | Base class for brace-style languages |
| `PythonStripper.java` | `copilot/src/main/java/copilot/context/` | Python comment stripping |

### Memory

| File | Location | Description |
|------|----------|-------------|
| `MemoryFact.java` | `copilot/src/main/java/copilot/memory/` | Memory fact model |
| `MemoryStore.java` | `copilot/src/main/java/copilot/memory/` | In-memory fact storage |

### Review / Diff

| File | Location | Description |
|------|----------|-------------|
| `PendingChange.java` | `copilot/src/main/java/copilot/review/` | Pending change model |
| `DiffLine.java` | `copilot/src/main/java/copilot/review/` | Diff line model |
| `DiffHunk.java` | `copilot/src/main/java/copilot/review/` | Diff hunk model |
| `DiffComputer.java` | `copilot/src/main/java/copilot/review/` | Diff computation |
| `CrossRefDetector.java` | `copilot/src/main/java/copilot/review/` | Cross-reference detection |

### Build Tools

| File | Location | Description |
|------|----------|-------------|
| `MavenBuildTool.java` | `maven-tools/src/main/java/copilot/maven/` | Maven build integration |
| `GradleBuildTool.java` | `gradle-tools/src/main/java/copilot/gradle/` | Gradle build integration |

---

## ⚠️ CATEGORY 2: Partially Agnostic (Requires Refactoring)

These components have **mixed dependencies** - core logic is agnostic but they depend on IntelliJ for specific functionality.

### Context Manager

| File | Location | Agnostic Parts | IntelliJ Dependencies |
|------|----------|----------------|----------------------|
| `ContextManager.java` | `copilot/src/main/java/copilot/context/` | Core file tracking, snapshot generation | `PersistentStateComponent`, VFS listeners, `VirtualFile` |

**Refactoring Strategy:**
```
copilot-base/
└── ContextManager.java (core logic only)
    - addEntry()
    - removeEntry()  
    - getEntries()
    - buildContextBlock()
    
copilot-intellij/
└── IntelliJContextManager.java (IDE integration)
    - VFS listener registration
    - PersistentStateComponent implementation
```

### Agent Plan Panel

| File | Location | Agnostic Parts | IntelliJ Dependencies |
|------|----------|----------------|----------------------|
| `AgentPlanPanel.java` | `copilot/src/main/java/copilot/chat/` | Plan display/rendering | `JPanel`, IntelliJ UI components |

**Refactoring Strategy:** Move to `copilot-common-ui` or `copilot-intellij`

---

## 🟡 CATEGORY 3: IDE-Specific (Stay in copilot-intellij)

These components are inherently tied to IntelliJ's architecture:

| File | Location | Reason |
|------|----------|--------|
| `CopilotChatPanel.java` | `copilot/src/main/java/copilot/chat/` | ToolWindow integration, IntelliJ UI |
| `SectionEditorPanel.java` | `copilot/src/main/java/copilot/chat/` | IntelliJ editor integration |
| `ContextPanel.java` | `copilot/src/main/java/copilot/context/` | IntelliJ UI components |
| `ReviewPanel.java` | `copilot/src/main/java/copilot/review/` | IntelliJ diff viewer integration |
| `LoadingButton.java` | `copilot/src/main/java/copilot/ui/` | IntelliJ button styling |
| `CopilotChatToolWindowFactory.java` | `copilot/src/main/java/copilot/chat/` | ToolWindow factory (IntelliJ-specific) |
| `CopilotSettingsForm.java` | `copilot/src/main/java/copilot/` | Settings UI |
| `CopilotUtil.java` | `copilot/src/main/java/copilot/` | IDE utilities, request cancellation |

---

## Summary Statistics

### By Category

| Category | Count | Effort to Extract |
|----------|-------|-------------------|
| Fully Agnostic | 42 files | **0 days** - just move |
| Partially Agnostic | 2 files | **3-5 days** - refactor |
| IDE-Specific | 8 files | **N/A** - keep as-is |

### By Module

| Module | Files | Status |
|--------|-------|--------|
| `tools-api` | 3 | ✅ Ready to move |
| `copilot-base` (proposed) | 42 | ✅ Ready to move |
| `copilot-intellij` (proposed) | 10 | ⚠️ Needs refactoring |
| `maven-tools` | 1 | ✅ Ready to move |
| `gradle-tools` | 1 | ✅ Ready to move |

---

## Dependencies Analysis

### copilot-base External Dependencies

```
copilot-base/
├── com.google.code.gson:gson:2.10.1          (JSON parsing)
├── org.jetbrains.annotations:annotations:24.1.0  (Annotations)
└── java.base                                  (Standard Java)
```

**No external dependencies required for core functionality!**

### copilot-intellij External Dependencies

```
copilot-intellij/
├── com.intellij:core-api:233.11799.240        (IntelliJ API)
├── com.intellij:ui-designer:*                 (UI Designer runtime)
└── [project]:copilot-base                     (Our base module)
```

---

## Migration Checklist

### Phase 1: Core Extraction
- [ ] Create `copilot-base` module structure
- [ ] Move all Category 1 files
- [ ] Update imports in remaining code
- [ ] Verify compilation
- [ ] Run existing tests

### Phase 2: Refactoring
- [ ] Extract `ContextManager` core logic
- [ ] Create IntelliJ adapter
- [ ] Move UI components to `copilot-intellij`
- [ ] Update all references

### Phase 3: Verification
- [ ] IntelliJ plugin works identically
- [ ] No regressions in functionality
- [ ] Documentation updated

---

## Recommendations

1. **Start with Category 1** - Lowest risk, highest reward
2. **Keep backward compatibility** - Don't break existing API
3. **Test after each move** - Ensure nothing breaks
4. **Document public API** - Clear contract for consumers
5. **Consider semantic versioning** - `copilot-base` as separate release

---

*This inventory will be updated as refactoring progresses.*
