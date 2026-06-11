# Copilot-Base Implementation Checklist

## Pre-Implementation

### Project Setup
- [ ] Create `copilot-base` directory structure
- [ ] Configure `build.gradle.kts` for base module
- [ ] Set up Maven publishing configuration
- [ ] Update `settings.gradle.kts` with new modules
- [ ] Create initial commit for module structure

---

## Phase 1: Core Extraction

### Agent Module (`copilot.core.agent`)

#### Files to Migrate
- [ ] `AgentSession.java`
- [ ] `AgentCallback.java`
- [ ] `Phase.java`
- [ ] `PhaseController.java`

#### Tasks
- [ ] Create `copilot/core/agent/` package structure
- [ ] Move files and update package declarations
- [ ] Remove IntelliJ imports from moved files
- [ ] Verify compilation of agent module

#### Verification
```bash
# Check for remaining IntelliJ dependencies
grep -r "import com.intellij" copilot-base/src/main/java/copilot/core/agent/
# Should return nothing
```

---

### Chat Module (`copilot.core.chat`)

#### Files to Migrate
- [ ] `ChatMessage.java`
- [ ] `ConversationHistory.java`
- [ ] `ChatMode.java`

#### Tasks
- [ ] Create `copilot/core/chat/` package structure
- [ ] Move files and update package declarations
- [ ] Verify no external dependencies

---

### Context Module (`copilot.core.context`)

#### Files to Migrate (Core Only)
- [ ] `ContextManager.java` (core logic only - see notes)
- [ ] `StrippedGenerator.java`
- [ ] `LanguageStripper.java`
- [ ] `BraceLanguageStripper.java`
- [ ] `PythonStripper.java`

#### Tasks
- [ ] Create `copilot/core/context/` package structure
- [ ] Move core logic to base module
- [ ] Note: Full ContextManager requires IDE adapter (see Phase 2)

---

### Tools Module (`copilot.core.tools`)

#### Files to Migrate

**API Interface**
- [ ] `AgentTool.java`
- [ ] `PathGuard.java`
- [ ] `ProcessRunner.java`

**Tool Implementations** (all in `tools/impl/`)
- [ ] `GetCurrentFileTool.java`
- [ ] `ListFilesTool.java`
- [ ] `AddToContextTool.java`
- [ ] `RemoveFromContextTool.java`
- [ ] `ListContextTool.java`
- [ ] `ClearContextTool.java`
- [ ] `ReadFileTool.java`
- [ ] `ReplaceInFileTool.java`
- [ ] `CreateFileTool.java`
- [ ] `SearchInFilesTool.java`
- [ ] `ScanProblemsTool.java`
- [ ] `GetIDEProblemsTool.java`
- [ ] `CompileProjectTool.java`
- [ ] `RunTestsTool.java`
- [ ] `CreatePlanTool.java`
- [ ] `UpdatePlanTool.java`
- [ ] `FinishTaskTool.java`
- [ ] `CompletePhaseTool.java`
- [ ] `RememberTool.java`

#### Tasks
- [ ] Create `copilot/core/tools/api/` and `copilot/core/tools/impl/` directories
- [ ] Move API interfaces first
- [ ] Move tool implementations
- [ ] Update all imports in tool files
- [ ] Verify no IntelliJ dependencies remain

---

### Memory Module (`copilot.core.memory`)

#### Files to Migrate
- [ ] `MemoryFact.java`
- [ ] `MemoryStore.java`

#### Tasks
- [ ] Create `copilot/core/memory/` package structure
- [ ] Move files and update package declarations

---

### Review Module (`copilot.core.review`)

#### Files to Migrate
- [ ] `PendingChange.java`
- [ ] `DiffLine.java`
- [ ] `DiffHunk.java`
- [ ] `DiffComputer.java`
- [ ] `CrossRefDetector.java`

#### Tasks
- [ ] Create `copilot/core/review/` package structure
- [ ] Move files and update package declarations

---

### Util Module (`copilot.core.util`) - NEW

#### Files to Create/Migrate
- [ ] `JsonUtils.java`
- [ ] `StringUtils.java`
- [ ] `IOUtils.java`

#### Tasks
- [ ] Create `copilot/core/util/` package structure
- [ ] Extract common utilities from existing code

---

## Phase 2: IntelliJ Adapter

### IntelliJ Context Manager

#### Files to Create
- [ ] `IntelliJContextManager.java`
- [ ] `IntelliJAgentSession.java` (if needed)

#### Tasks
- [ ] Create `copilot/intellij/context/` package structure
- [ ] Implement `IntelliJContextManager` extending base `ContextManager`
- [ ] Add VFS listener registration
- [ ] Implement file reading via IntelliJ VFS API
- [ ] Handle snapshot regeneration

#### Verification
```java
// Test that IntelliJ-specific code compiles
cd copilot-intellij && ./gradlew build
```

---

### IntelliJ UI Components

#### Files to Migrate/Update
- [ ] `CopilotChatPanel.java`
- [ ] `AgentPlanPanel.java`
- [ ] `SectionEditorPanel.java`
- [ ] `ContextPanel.java`
- [ ] `ReviewPanel.java`
- [ ] `LoadingButton.java`

#### Tasks
- [ ] Identify which UI components can be shared
- [ ] Move reusable components to `copilot-common-ui`
- [ ] Keep IntelliJ-specific UI in `copilot-intellij/ui/`
- [ ] Update all references

---

### IntelliJ Integration Updates

#### Files to Update
- [ ] `CopilotChatToolWindowFactory.java`
- [ ] `CopilotSettingsForm.java`
- [ ] `CopilotUtil.java`

#### Tasks
- [ ] Update imports to use new module structure
- [ ] Replace direct ContextManager usage with adapter
- [ ] Verify all functionality preserved

---

## Phase 3: Eclipse Integration

### Module Setup

#### Files to Create
- [ ] `copilot-eclipse/build.gradle.kts`
- [ ] `copilot-eclipse/plugin.xml`

#### Tasks
- [ ] Configure Tycho plugin for Eclipse
- [ ] Set up dependencies on copilot-base
- [ ] Add Eclipse JDT dependencies

---

### Eclipse Context Manager

#### Files to Create
- [ ] `EclipseContextManager.java`

#### Tasks
- [ ] Implement extending base `ContextManager`
- [ ] Integrate with JDT resource listeners
- [ ] Handle file change events via Eclipse API

---

### Eclipse UI Components

#### Files to Create
- [ ] `CopilotChatView.java`
- [ ] Toolbar contributions
- [ ] View contributions

#### Tasks
- [ ] Implement Eclipse view for chat
- [ ] Add toolbar buttons
- [ ] Integrate with Eclipse editor

---

## Phase 4: Testing

### Unit Tests

#### Test Files to Create
- [ ] `AgentSessionTest.java`
- [ ] `ConversationHistoryTest.java`
- [ ] `ContextManagerTest.java`
- [ ] `ToolTests.java` (all tools)
- [ ] `MemoryStoreTest.java`

#### Tasks
- [ ] Write tests for each core class
- [ ] Achieve >80% coverage
- [ ] Run all tests successfully

---

### Integration Tests

#### Test Files to Create
- [ ] `IntelliJIntegrationTest.java`
- [ ] `EclipseIntegrationTest.java`

#### Tasks
- [ ] Test IntelliJ plugin end-to-end
- [ ] Test Eclipse integration basic functionality
- [ ] Verify no regressions

---

## Phase 5: Documentation

### API Documentation

#### Files to Create/Update
- [ ] Update `API-Reference.md`
- [ ] Add Javadoc comments to all public classes
- [ ] Create usage examples

#### Tasks
- [ ] Document all public APIs
- [ ] Provide code examples
- [ ] Document breaking changes clearly

---

### Migration Documentation

#### Files to Create/Update
- [ ] Update `Migration-Guide.md`
- [ ] Create changelog
- [ ] Document versioning strategy

#### Tasks
- [ ] List all breaking changes
- [ ] Provide migration examples
- [ ] Document deprecation timeline

---

## Phase 6: Release Preparation

### Code Quality

#### Tasks
- [ ] Run security audit
- [ ] Check for memory leaks
- [ ] Verify thread safety
- [ ] Performance profiling

---

### Build & Publish

#### Tasks
- [ ] Configure release pipeline
- [ ] Test publishing to Maven Central
- [ ] Create GitHub release
- [ ] Update documentation site

---

## Verification Checklist

### Module Structure
- [ ] `copilot-base` compiles without IntelliJ dependencies
- [ ] `copilot-intellij` can depend on `copilot-base`
- [ ] `copilot-eclipse` can depend on `copilot-base`
- [ ] No circular dependencies

### Functionality
- [ ] All core agent logic works
- [ ] Tools execute correctly
- [ ] Context management functional
- [ ] Conversation history preserved

### IDE Integration
- [ ] IntelliJ plugin works identically to original
- [ ] Eclipse integration functional
- [ ] No regressions in existing functionality

### Quality
- [ ] Tests passing (>80% coverage)
- [ ] Documentation complete
- [ ] Security issues resolved

---

## Rollback Criteria

If any phase fails:
1. Document the issue
2. Create a fix branch
3. Revert changes if necessary
4. Update timeline accordingly

---

*This checklist will be updated as implementation progresses.*
