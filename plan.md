# Agentic Loop — Plugin-Enforced Workflow Plan

## Bottom line

The core thesis is right: **"the model asks, the plugin enforces."** But this is a
**migration (prompt → plugin), not a rewrite** — roughly 70% of the desired structure
already exists in `AgentSession`, only as prompt text:

- `staticPromptSection()` — hard rules + failure-mode table (LOOP / DRIFT / BLIND_COMPILE
  / STALE_READ / PREMATURE_FINISH) + tool list.
- `defaultAgenticWorkflowSection()` — phases ANALYSE → PLAN → RESOLVE → FINISH and the
  inner loop SETUP → DETERMINE → ACT → TEST → REASON.
- `AgentSession` already rewrites the system message every turn, already injects the plan,
  and already does duplicate-call loop detection (`callsSinceLastWrite`).
- `ContextManager.buildContextBlock()` already builds a "# Dynamic Context" manifest.

The real value concentrates in three moves:

1. **Push** the current phase block instead of trusting the model to fetch it.
2. **One hard gate at the finish line** (VERIFY → DONE) with escape hatches.
3. **Semantic stuck-detection** — the piece that actually fixes the `test → tweak → test`
   loop, which the original plan didn't mention at all.

## Design bugs in the original plan (fixed here)

### Bug #1 — loop topology was a regression
Original: `Analyse → Plan → Implement → Test → Analyse`. This collapses the existing
two-level structure (macro-phases + per-change inner loop) and re-analyses from scratch
every iteration. Corrected topology:

```
ANALYSE → PLAN → [ IMPLEMENT ⇄ VERIFY ]*  → DONE
                        ↑__________|
                   (VERIFY → ANALYSE only on re-diagnosis)
```

- `Test` is **not a phase** — it's the gate *out* of every IMPLEMENT cycle.
- After VERIFY: loop back to IMPLEMENT (tests failed / more milestones), forward to DONE
  (all green), or — rarely — back to ANALYSE when the diagnosis itself was wrong.

### Bug #2 — "call get_workflow before any action" is itself a buried instruction
The whole premise is that the model ignores buried mandates, so a system prompt whose one
rule is "always call `get_workflow` first" is exactly that failure mode. **Fix: push, don't
pull.** The plugin owns the phase state, so it should always inject the current phase's
instructions (and drop all others) via the per-turn system-message rewrite that already
exists. `get_workflow` survives only as an optional "show detailed examples" affordance —
not load-bearing.

## Blind spots (the deferred design questions)

1. **Semantic stuck-detection.** `callsSinceLastWrite` only catches *byte-identical*
   tool+args. The real loop is `test → tweak → test → tweak`, each edit slightly different,
   never converging. Need a **progress signal**: track consecutive failed VERIFYs /
   no reduction in error count over N edits → force `ASK_USER`. **This is the original bug.**
2. **Gate deadlock = a new infinite loop.** Invariant to design to: *from every state, for
   every precondition outcome, at least one tool call exists that the plugin will accept*
   (even if that move is `ASK_USER` or `waive_gate`). Otherwise drift-loops become
   deadlock-loops.
3. **Stop-and-ask after N iterations** must be a plugin-enforced counter, not a prompt line.
   A *soft* mid-limit separate from the hard `maxIterations` cap.

## Decisions (settled)

- **Inner loop:** stays as **guidance text inside the IMPLEMENT phase**, not gated states.
  The state machine is the 5 macro states only. Keeps `PhaseController` simple and avoids
  deadlock surface.
- **Gate rigidity:** **hard for finish, soft elsewhere.** Only `VERIFY → DONE` is a true
  ACCEPT/REJECT block. All other transitions steer but pass through. The one
  deadlock-capable gate gets `waive_gate(reason)` + `ASK_USER` escapes.

## Architecture

**New component: `PhaseController` (plugin-side state machine).** One per project,
persisted. Owns: current phase, per-phase precondition checkers, and progress counters.
This is the "plugin enforces" layer that doesn't exist yet.

- `enum Phase { ANALYSE, PLAN, IMPLEMENT, VERIFY, DONE }`
- Persist by extending `ContextManager.State` (already a `PersistentStateComponent`) —
  don't invent new storage.

## Build order

| Phase | What | Risk |
|---|---|---|
| 0 | `Phase` enum + `PhaseController`, persisted via `ContextManager.State` | low |
| 1 | Split workflow text into per-phase blocks; **push** current block + manifest, drop the rest (kills bug #2) | low |
| 2 | `complete_phase(phase)` — checkers for all transitions, **hard ACCEPT/REJECT only on VERIFY→DONE** + `waive_gate` / `ASK_USER` escapes | medium |
| 3 | Progress tracking: `consecutiveFailedVerifies` + error-count trend → soft ASK_USER nudge at bottom of context (blind spot #1 — the original bug) | medium |
| 4 | Manifest header: phase + PSI structure (wrap in `ReadAction`, handle dumb/indexing mode), capped rolling evicted list | medium |
| 5 | Auto-inject compile/test file:line output after VERIFY | low |

Phases 0–2 are the spine and deliver "plugin enforces the finish line" on their own.
**Phase 3 is non-optional** even though it's listed later — it's the piece that fixes the
`test → tweak → test` loop that started this whole discussion.

### Phase 2 — precondition signals (concrete, observable)
- `ANALYSE → PLAN`: ≥1 file pinned/read this phase.
- `PLAN → IMPLEMENT`: `currentPlan != null` and parses into ≥1 milestone with relevant-files
  (structure already enforced in `CreatePlanTool`).
- `IMPLEMENT → VERIFY`: ≥1 successful `replace_in_file` / `create_file` since entering IMPLEMENT.
- `VERIFY → DONE` **(hard gate)**: a `run_tests` / `compile_project` with a **passing** result
  recorded *after* the last edit. Track with a `lastVerifyClean` flag set by those tools,
  cleared by any write tool (mirror the existing `callsSinceLastWrite` reset logic).
- Every gate returns ACCEPT or a rejection that **names the one legal next move** — never a
  dead end. `waive_gate(reason)` logged to the manifest for auditability.

### Phase 4 — manifest header
```
=== WORKING CONTEXT ===
Phase: IMPLEMENT  (milestone 2/4)
Loaded: Foo.java [Foo: bar(), baz()] · Bar.java [Bar: init()]
Evicted (re-pin only if needed): Old.java
======================
```
- Structure via PSI inside a `ReadAction`; handle dumb mode (fall back to "structure
  unavailable, indexing").
- Cap the evicted list (rolling, last ~5). Phrase as "re-pin only if needed" — **not** a
  prohibition; the model must still be able to re-fetch.

## Cross-cutting rule — primacy / recency

- **Invariants** (current phase, manifest) → top of context (primacy).
- **Transient nudges** (loop warning, stuck → ask) → bottom of context (recency).

The architecture already splits system-message-rewrite (top) from appended tool results
(bottom), so this falls out naturally.

---

# Memory — mid/long-term

## The gap

Current memory tiers:

| Tier | Mechanism | State |
|---|---|---|
| **Working** (this turn) | system-message rewrite: pins, plan, (soon) phase + manifest | strong |
| **Session** (this conversation) | `ConversationHistory` + `callCompressionLlm()` | exists, but **lossy** |
| **Long-term** (across sessions) | `saveSnapshot()` → `.kalynx/snapshots/*.md` | **write-only — never read back** |

Headline problem: **compression destroys, and snapshots are a journal nobody opens.** There
is no tier that *accumulates durable knowledge*. The fix is to add a semantic store and
change *when* extraction happens — not to build a better compressor.

## Core trick: extract-then-compress

`maybeCompress()` currently throws information away. Instead, **before** compressing, run a
cheap extraction pass that pulls durable facts out of the turns about to be crushed and
writes them to a persistent store. Compression loss then stops mattering — the important
content already escaped.

Separate two things currently conflated:
- **Episodic** ("what happened") — the snapshot journal. Keep as-is, just make it *readable*.
- **Semantic** ("what's true about this project") — distilled facts. **The missing tier.**

(This mirrors the `MEMORY.md` pattern Claude Code uses: one-fact entries, loaded at session
start, written deliberately, recalled by relevance.)

## Tricks, cheapest first

1. **Semantic store — `.kalynx/memory.md` (the missing tier).** Persist via
   `ContextManager.State`. Load into the system message at session start. Start as
   append-with-dedup markdown. The 80/20.
2. **Retrieval keyed by file path — the unfair advantage.** When a file enters context,
   auto-surface facts tagged to it. Cheap, deterministic, no embeddings. Tag facts by file
   path + phase on write.
3. **Make snapshots readable.** (a) Session-start "last time you were working on X, left off
   at Y" from the newest snapshot; (b) a `search_history` tool over `.kalynx/snapshots/`.
4. **Protect anchor + plan from compression.** Pin the north-star above the compression
   boundary so it never degrades over long sessions.
5. **Capture at phase transitions (ties into `PhaseController`).** `complete_phase` is a
   structured memory-write hook: record diagnosis on `PLAN→IMPLEMENT`, record what
   changed/learned on `VERIFY→DONE`. Memory becomes a side-effect of the enforced workflow.

## Decisions (settled)

- **Writes:** **both** — plugin auto-captures a guaranteed baseline at phase transitions and
  before compression; a `remember(fact)` tool lets the model top up. Plugin baseline avoids
  the buried-instruction problem; model top-up adds anything the plugin missed.
- **Retrieval:** **both, as layers** — tags always on, embeddings as an opt-in augmentation
  with graceful fallback (see below).

## Retrieval — tags + embeddings as layers

"Both" must mean **"tags always, embeddings when available"** because backends vary
(OpenAI-compatible, Mistral/Codestral, local LM Studio) and many — especially local — expose
no embeddings endpoint. Hard-depending on embeddings breaks memory for the users most likely
to run the plugin.

- **Tag-based = floor, always on.** Deterministic, zero deps, free. Facts about a pinned file
  always surface.
- **Embedding-based = ceiling, opt-in.** Runs only when an embeddings provider is configured
  and healthy (probe it, like `fetchContextWindow` probes the model). Catches conceptually
  related facts not tied to a pinned file. Absent → silently degrade to tag-only.

Implications:

| Concern | Tags | Embeddings | Combined |
|---|---|---|---|
| Dependency | none | needs embeddings endpoint (portability risk) | must degrade to tags |
| Storage | trivial | brute-force cosine over in-memory array (no vector DB for 100s of facts) | trivial |
| Indexing | none | embed on write; **re-embed on update** | append-only simpler |
| Tuning | none | threshold + top-k; too loose = noise/poisoning | tag matches win ties |
| Latency/cost | instant | one embed call per recall + write | OK if embeddings async/optional |
| Config | none | new endpoint+model setting + capability probe | extra settings |

Two to watch:
- **Budget cap is mandatory.** A fact can surface from both channels — dedup by fact ID, then
  hard-cap the merged set (~5–8 facts / N tokens) regardless of source, or memory bloats the
  context it was meant to keep lean.
- **Re-embed on edit.** Update-in-place hygiene means facts get rewritten; each rewrite must
  re-embed or the vector drifts from the text.

Architecture: one seam — `MemoryRetriever { List<Fact> recall(context) }` — two
implementations. Controller always queries tags, conditionally merges embeddings when a
provider is healthy. "Both" becomes a config/capability decision, not a code fork.

**Build order:** tags v1 (proves write→recall end to end), embeddings v2 as a drop-in
augmentation. The tag layer is both foundation *and* fallback — it must exist and be correct
first.

## Risk: stale / poisoned memory

A wrong durable fact is worse than none — it misleads every future session silently, and for
a code agent facts go stale the moment code changes. So:
- **Timestamp + source** every fact.
- Treat stored facts about code as **"verify before trusting,"** not ground truth.
- **Update-in-place + newest-wins**, not append-only growth.
- **Size cap** with eviction (same discipline as the manifest's evicted list).

## Recommended starting point

Tricks **#1 + #2 + #5** (semantic store, file-keyed recall, phase-transition capture) — the
full write-the-right-fact / recall-it-at-the-right-moment loop, slotted into the
`PhaseController` work rather than run as a separate project. #3 and #4 are follow-ups.