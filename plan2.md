Plan 2 — Within-Phase Effectiveness Layer
Bottom line
Plan 1 enforces the workflow at its boundaries — phase gates, finish-line verification, stuck-detection at transitions. But the loops that started this whole discussion form inside a phase, between gates. This plan attacks that gap: collapse the test→tweak→test cycle from the inside so the gates rarely have to catch anything. These are amplifiers on Plan 1, not replacements — most assume PhaseController, the manifest, and progress counters already exist.

Ordered by impact.

1. Precise edit-rejection loop (highest leverage)
   Problem: The dominant within-phase thrash is edits that don't apply — stale line numbers, whitespace mismatch, anchor drift. A generic failure response sends the model into guess-and-retry.

Fix: When replace_in_file fails, return why in one line, naming the corrective action:

EDIT REJECTED: anchor not found — re-read lines 40-60, text has changed.
A precise rejection turns a retry loop into a single corrective move.
Never return a bare "edit failed" — every rejection names the next action.
Mirrors the gate principle from Plan 1: no dead ends, always one legal move forward.
Risk: low. Build: small — it's response wording + failure-cause classification on the existing tool.

2. IDE-as-validator after every write (highest leverage)
   Problem: The model's self-assessment of its own edits is one of the least reliable signals available. Plan 1 auto-injects compile/test output at VERIFY — but batching feedback to the phase boundary lets errors compound.

Fix: After every write tool, run the IntelliJ compiler / analysis daemon / inspections on the touched file and surface new errors the edit introduced, immediately, at the bottom of context:

src/Foo.java:42: cannot resolve symbol 'baz'
Don't ask the model "did that work?" — tell it.
Tight per-edit feedback beats end-of-phase batching.
Wrap in ReadAction; handle dumb/indexing mode (defer, note "validation pending — indexing").
Risk: low–medium (performance on large files — debounce / scope to the edited region). Build: medium.

3. ASK_USER with teeth
   Problem: ASK_USER exists in Plan 1 as an escape hatch, but the model treats it as last resort and grinds through bad iterations first.

Fix:

The plugin triggers it off the progress counter (consecutiveFailedVerifies), not the model's judgement.
Force a structured question: "Tried X and Y, both failed because Z. Option A / Option B?" — never "I'm stuck."
A good question at iteration 3 beats ten autonomous retries.
Risk: low. Build: small — wires into the existing progress counter (Plan 1, Phase 3).

4. Speculative-read suppression
   Problem: Models read files "just to check" before acting, bloating context — the original 30k-token problem.

Fix: When the model requests a read the manifest structure already answers, return the manifest slice instead of the full file:

Foo.java already loaded — structure: Foo { bar(), baz() }. Re-pin full file only if editing.
Cuts context bloat at the source.
Phrase as redirect, not prohibition — the model must still be able to force a real read when genuinely editing.
Risk: medium (false suppression of a read it actually needed). Build: medium — depends on Plan 1's PSI manifest.

5. Cost/latency budget as a visible signal
   Problem: Nothing tracks spend per task. No telemetry for you; no convergence pressure on the model.

Fix: Running cost in the manifest header:

Budget: ~12k tokens · 6 iterations
Gives you telemetry on which tasks blow up.
Gently pressures the model toward convergence — agents behave better when they can see they're spending.
Risk: low. Build: small — extends the Plan 1 manifest header.

6. Deterministic system-message prefix
   Problem: The system message is rewritten every turn. Reworded-but-unchanged instructions read as new information to the model, and break prompt caching.

Fix: Make stable sections (hard rules, current phase block) byte-identical turn-to-turn when nothing changed.

Stable prefix → prompt-cache hits → cheaper + faster.
Also stops the model treating a rephrased rule as a new instruction.
Only the genuinely dynamic parts (manifest, phase value, transient nudges) should differ between turns.
Risk: low. Build: small but cross-cutting — a discipline on the existing rewrite, not new code.

7. Model-owned scratchpad
   Problem: Compression destroys the in-flight reasoning thread. The semantic store (Plan 1 memory) captures durable facts, not the current working hypothesis.

Fix: One small, persistent, model-writable note that survives compression:

Scratchpad: NPE originates in ReviewManager.addReview() when authToken is null — testing guard clause.
Externalizes the reasoning thread compression would otherwise crush.
Cheaper and more reliable than hoping the semantic store catches in-flight thinking.
Pin above the compression boundary, like the anchor/plan.
Risk: low (model writes noise — cap size, single slot, overwrite not append). Build: small.

Recommended sequencing
Do #1 and #2 first. Together they collapse the test→tweak loop from the inside — the same problem the whole architecture circles. Plan 1's gates catch it at the boundary; #1 and #2 stop it forming in the first place.

Then #3 (cheap, leans on existing counters) and #6 (caching discipline, pays for itself immediately).

#4, #5, #7 are refinements once the core loop is tight.

Dependency note
All of these assume Plan 1's spine (PhaseController, PSI manifest, progress counters) is in place. None of them stand alone — they're the layer that makes the enforced workflow pleasant instead of merely correct.