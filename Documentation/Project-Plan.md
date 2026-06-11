# Copilot-Base Project Plan

## Executive Summary

This document outlines the complete project plan for decoupling IDE-agnostic logic from the Kalynx Copilot IntelliJ plugin into a reusable `copilot-base` library.

---

## Project Goals

1. **Extract IDE-agnostic core** - Move all non-IDE-specific code to `copilot-base`
2. **Enable cross-IDE support** - Make it easy to build Eclipse, VS Code, and other IDE integrations
3. **Maintain backward compatibility** - Existing IntelliJ plugin continues to work unchanged
4. **Provide clean API** - Well-documented public API for external consumers

---

## Project Structure

```
Kalynx-Copilot/
├── copilot-base/              # NEW: Core agnostic library
│   ├── src/main/java/copilot/core/
│   │   ├── agent/
│   │   │   ├── AgentSession.java
│   │   │   ├── AgentCallback.java
│   │   │   ├── Phase.java
│   │   │   └── PhaseController.java
│   │   ├── chat/
│   │   │   ├── ChatMessage.java
│   │   │   ├── ConversationHistory.java
│   │   │   └── ChatMode.java
│   │   ├── context/
│   │   │   ├── ContextManager.java
│   │   │   ├── StrippedGenerator.java
│   │   │   └── LanguageStripper.java
│   │   ├── tools/
│   │   │   ├── api/
│   │   │   │   ├── AgentTool.java
│   │   │   │   ├── PathGuard.java
│   │   │   │   └── ProcessRunner.java
│   │   │   └── impl/
│   │   │       ├── file/
│   │   │       ├── search/
│   │   │       ├── build/
│   │   │       └── plan/
│   │   ├── memory/
│   │   │   ├── MemoryFact.java
│   │   │   └── MemoryStore.java
│   │   └── review/
│   │       ├── PendingChange.java
│   │       └── DiffComputer.java
│   │
│   ├── build.gradle.kts
│   └── pom.xml
│
├── copilot-intellij/          # NEW: IntelliJ-specific integration
│   ├── src/main/java/copilot/intellij/
│   │   ├── ui/
│   │   │   ├── CopilotChatPanel.java
│   │   │   ├── AgentPlanPanel.java
│   │   │   └── ...
│   │   ├── context/
│   │   │   └── IntelliJContextManager.java
│   │   └── chat/
│   │       └── CopilotChatToolWindowFactory.java
│   │
│   ├── build.gradle.kts
│   └── plugin.xml
│
├── copilot-eclipse/           # NEW: Eclipse-specific integration
│   ├── src/main/java/copilot/eclipse/
│   │   ├── ui/
│   │   │   └── CopilotChatView.java
│   │   └── context/
│   │       └── EclipseContextManager.java
│   │
│   └── build.gradle.kts
│
├── copilot/                   # EXISTING: IntelliJ plugin (will use new modules)
│   └── src/main/java/copilot/
│       ├── ... (existing files, will be refactored to use new modules)
│
└── Documentation/
    ├── IDE-Agnostic-Decoupling-Plan.md
    ├── Agnostic-Component-Inventory.md
    ├── Copilot-Base-API-Design.md
    ├── Implementation-Roadmap.md
    ├── Module-Structure.md
    ├── Risk-Analysis.md
    ├── Implementation-Status.md
    └── API-Reference.md
```

---

## Milestone Breakdown

### Milestone 1: Foundation (Week 1)

**Goal:** Create `copilot-base` module structure and migrate core classes.

#### Tasks

| Task | Files | Effort |
|------|-------|--------|
| Create module structure | New directories, build files | 2 days |
| Migrate AgentSession | `AgentSession.java`, `AgentCallback.java` | 1 day |
| Migrate ChatMessage/History | `ChatMessage.java`, `ConversationHistory.java` | 1 day |
| Migrate tools-api | All tool interfaces and implementations | 2 days |
| Update imports in copilot | Fix all import statements | 1 day |

#### Deliverables
- [ ] `copilot-base` module compiles successfully
- [ ] All core agent classes moved to new package structure
- [ ] No IntelliJ dependencies in base module

---

### Milestone 2: Context Management (Week 2)

**Goal:** Refactor ContextManager and implement IDE adapters.

#### Tasks

| Task | Files | Effort |
|------|-------|--------|
| Extract ContextManager core | `ContextManager.java` (core logic) | 2 days |
| Create IntelliJ adapter | `IntelliJContextManager.java` | 1 day |
| Implement VFS integration | VFS listeners, snapshot generation | 2 days |
| Update copilot to use new API | Fix all ContextManager references | 1 day |

#### Deliverables
- [ ] ContextManager split into core + IDE-specific parts
- [ ] IntelliJ adapter fully functional
- [ ] No regressions in context management

---

### Milestone 3: UI Components (Week 2-3)

**Goal:** Organize UI components for sharing.

#### Tasks

| Task | Files | Effort |
|------|-------|--------|
| Identify reusable UI components | `LoadingButton.java`, etc. | 1 day |
| Move to copilot-common-ui | Reusable components | 2 days |
| Update IntelliJ to use common UI | Fix imports and references | 1 day |

#### Deliverables
- [ ] Common UI components extracted
- [ ] IntelliJ plugin uses common UI where possible

---

### Milestone 4: Build Tools (Week 3)

**Goal:** Ensure Maven/Gradle tools work standalone.

#### Tasks

| Task | Files | Effort |
|------|-------|--------|
| Verify MavenBuildTool independence | `MavenBuildTool.java` | 1 day |
| Verify GradleBuildTool independence | `GradleBuildTool.java` | 1 day |
| Create unified build interface | New abstraction layer | 1 day |

#### Deliverables
- [ ] Build tools work without IDE dependencies

---

### Milestone 5: Eclipse Integration (Week 3-4)

**Goal:** Implement Eclipse integration using copilot-base.

#### Tasks

| Task | Files | Effort |
|------|-------|--------|
| Create copilot-eclipse module | New module structure | 1 day |
| Implement EclipseContextManager | JDT integration | 2 days |
| Implement Eclipse UI | View, toolbar contributions | 2 days |
| Test basic functionality | End-to-end test | 1 day |

#### Deliverables
- [ ] Eclipse integration functional
- [ ] Basic chat and tools working

---

### Milestone 6: Stabilization (Week 4)

**Goal:** Testing, documentation, and release preparation.

#### Tasks

| Task | Files | Effort |
|------|-------|--------|
| Write unit tests | All core classes | 2 days |
| Write integration tests | IntelliJ + Eclipse | 1 day |
| Document public API | API-Reference.md update | 1 day |
| Create migration guide | Migration-Guide.md | 1 day |
| Security audit | All components | 1 day |

#### Deliverables
- [ ] Test coverage >80%
- [ ] Documentation complete
- [ ] Security issues resolved

---

## Risk Management

### Identified Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Breaking existing functionality | HIGH | MEDIUM | Maintain backward compatibility; gradual migration |
| Incomplete Eclipse integration | MEDIUM | LOW | Focus on core first; iterative enhancement |
| Performance regression | MEDIUM | LOW | Profile before/after; optimize hot paths |

---

## Success Criteria

### Functionality
- [ ] 100% of core agent logic in `copilot-base`
- [ ] IntelliJ plugin works identically to pre-refactor
- [ ] Eclipse integration supports basic chat and tools

### Quality
- [ ] All tests passing (>80% coverage)
- [ ] No critical security vulnerabilities
- [ ] Documentation complete and accurate

### Usability
- [ ] Easy to extend to new IDEs
- [ ] Clear API documentation
- [ ] Working examples provided

---

## Release Plan

### Version 0.1.0 (Initial Release)
- Core agent logic
- Tool interface and implementations
- Basic context management
- IntelliJ integration (via adapter)

### Version 0.2.0 (Eclipse Support)
- Eclipse integration complete
- Common UI components
- Improved documentation

### Version 1.0.0 (Stable Release)
- All features stable
- API frozen for 1.x series
- Production-ready

---

## Maintenance Plan

### Ongoing Tasks
- Monitor for IntelliJ API changes
- Update dependencies as needed
- Accept community contributions for new IDEs
- Improve documentation based on user feedback

### Backward Compatibility
- Semantic versioning followed
- Deprecated classes redirect to new locations
- Migration guide provided for breaking changes

---

## Contact & Support

For questions or issues:
- GitHub Issues: [project]/issues
- Documentation: `Documentation/` directory
- Example code: `examples/` directory

---

*This project plan will be updated regularly as implementation progresses.*
