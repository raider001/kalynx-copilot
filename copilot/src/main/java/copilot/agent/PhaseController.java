package copilot.agent;

import copilot.context.ContextManager;
import copilot.memory.MemoryStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin-side phase state machine.
 *
 * Owns phase transitions, precondition checking, stuck-detection counters,
 * the evicted-files manifest, and the === WORKING CONTEXT === header injected
 * at the top of every system message.
 */
public class PhaseController {

    private static final int STUCK_THRESHOLD = 3;
    private static final int MAX_EVICTED     = 5;

    private final ContextManager cm;
    private final MemoryStore    memoryStore;

    /** Relative path of the last successfully edited file — drives inline validation. */
    private String lastEditedRelPath = null;

    public PhaseController(ContextManager cm) {
        this.cm          = cm;
        this.memoryStore = new MemoryStore(cm);
    }

    // ── Phase accessors ───────────────────────────────────────────────────────────

    public Phase getPhase() { return cm.getCurrentPhase(); }

    public void setPhase(Phase phase) {
        cm.setCurrentPhase(phase);
        cm.setHasReadFileThisPhase(false);
        cm.setHasEditedThisPhase(false);
        lastEditedRelPath = null;
        if (phase == Phase.IMPLEMENT) {
            cm.setLastVerifyClean(false); // entering implement means new changes needed
        }
    }

    /** Call at the start of a new user message when the previous task completed. */
    public void resetForNewTask() {
        cm.setCurrentPhase(Phase.ANALYSE);
        cm.setHasReadFileThisPhase(false);
        cm.setHasEditedThisPhase(false);
        cm.setLastVerifyClean(false);
        cm.setConsecutiveFailedVerifies(0);
        cm.setLastErrorCount(-1);
        cm.setLastVerifyOutput("");
        lastEditedRelPath = null;
    }

    // ── Notification hooks ────────────────────────────────────────────────────────

    /** Called by add_to_context / read_file tools. */
    public void notifyFileRead() {
        cm.setHasReadFileThisPhase(true);
    }

    /** Called by replace_in_file / create_file tools on success. */
    public void notifyEdit() {
        cm.setHasEditedThisPhase(true);
        cm.setLastVerifyClean(false);
    }

    /** Overload that also records the path for per-edit inline validation. */
    public void notifyEdit(String relPath) {
        lastEditedRelPath = relPath;
        notifyEdit();
    }

    public String getLastEditedRelPath() { return lastEditedRelPath; }

    /** Called by compile_project / run_tests after each execution. */
    public void notifyVerifyResult(boolean passed, int errorCount, String output) {
        cm.setLastVerifyOutput(output != null ? output : "");
        if (passed) {
            cm.setLastVerifyClean(true);
            cm.setConsecutiveFailedVerifies(0);
            cm.setLastErrorCount(0);
        } else {
            cm.setLastVerifyClean(false);
            int prev = cm.getLastErrorCount();
            boolean improving = prev > 0 && errorCount < prev;
            cm.setLastErrorCount(errorCount);
            if (!improving) {
                cm.setConsecutiveFailedVerifies(cm.getConsecutiveFailedVerifies() + 1);
            } else {
                cm.setConsecutiveFailedVerifies(0);
            }
        }
    }

    /** Called when a file is unpinned — adds to rolling evicted list for the manifest. */
    public void notifyEvicted(String relativePath) {
        List<String> evicted = new ArrayList<>(cm.getEvictedFiles());
        evicted.remove(relativePath);
        evicted.add(0, relativePath);
        if (evicted.size() > MAX_EVICTED) evicted = evicted.subList(0, MAX_EVICTED);
        cm.setEvictedFiles(evicted);
    }

    // ── Transitions ───────────────────────────────────────────────────────────────

    /**
     * Attempts a phase transition. Returns a {@link TransitionResult}.
     * Hard gate: VERIFY → DONE requires lastVerifyClean unless waived.
     * All other gates are soft — they warn but allow the transition through.
     */
    public TransitionResult transition(Phase from, Phase to, String waiveReason) {
        if (from != getPhase()) {
            return TransitionResult.reject(
                "Current phase is " + getPhase().name() + ", not " + from.name() + ". "
              + "Pass the correct current_phase.");
        }

        // Hard gate
        if (from == Phase.VERIFY && to == Phase.DONE) {
            boolean waived = waiveReason != null && !waiveReason.isBlank();
            if (!cm.isLastVerifyClean() && !waived) {
                return TransitionResult.reject(
                    "REJECTED: Cannot transition VERIFY → DONE. "
                  + "The last compile/test did not pass, or no verify was run after the last code change. "
                  + "Run compile_project or run_tests first, then call complete_phase again. "
                  + "If this task has no testable output (docs-only, config change, etc.), "
                  + "provide waive_reason explaining why.");
            }
        }

        String softWarning = buildSoftWarning(from);
        setPhase(to);

        if (softWarning != null) return TransitionResult.warnAndAccept(softWarning, to);
        return TransitionResult.accept(to,
                waiveReason != null && !waiveReason.isBlank() ? "Gate waived: " + waiveReason : null);
    }

    private String buildSoftWarning(Phase from) {
        return switch (from) {
            case ANALYSE -> !cm.isHasReadFileThisPhase()
                ? "⚠ Leaving ANALYSE without reading any files. Proceed only if no exploration was needed."
                : null;
            case PLAN -> (cm.getCurrentPlan() == null || cm.getCurrentPlan().isBlank())
                ? "⚠ Leaving PLAN without a stored plan. Consider calling create_plan first."
                : null;
            case IMPLEMENT -> !cm.isHasEditedThisPhase()
                ? "⚠ Leaving IMPLEMENT without making any code changes."
                : null;
            default -> null;
        };
    }

    public record TransitionResult(boolean accepted, String message, Phase newPhase) {
        static TransitionResult reject(String msg)              { return new TransitionResult(false, msg, null); }
        static TransitionResult accept(Phase p, String msg)     { return new TransitionResult(true, msg, p); }
        static TransitionResult warnAndAccept(String w, Phase p){ return new TransitionResult(true, w, p); }
    }

    // ── Stuck nudge ───────────────────────────────────────────────────────────────

    /** Returns a warning injected at the bottom of context (recency) when stuck. */
    public String buildStuckNudge() {
        int failed = cm.getConsecutiveFailedVerifies();
        if (failed < STUCK_THRESHOLD) return "";
        return "=== STUCK: " + failed + " consecutive verify failures ===\n"
             + "REQUIRED NEXT ACTION: call ask_user immediately.\n"
             + "Format your question as:\n"
             + "  \"I tried [approach 1] and [approach 2]. Both failed because [specific error]. "
             + "Should I: (A) [concrete option] or (B) [concrete option]?\"\n"
             + "Do NOT attempt another code change. Do NOT call finish_task.\n"
             + "===";
    }

    // ── Manifest header ───────────────────────────────────────────────────────────

    /** Builds the === WORKING CONTEXT === block injected at the top of the system message. */
    public String buildManifestHeader(String basePath) {
        return buildManifestHeader(basePath, 0, 0);
    }

    /** Builds the manifest header including cost/iteration budget. */
    public String buildManifestHeader(String basePath, int iterations, int tokenEstimate) {
        StringBuilder sb = new StringBuilder("=== WORKING CONTEXT ===\n");

        // Phase + optional milestone progress
        String phaseLabel = getPhase().name();
        String plan = cm.getCurrentPlan();
        if (plan != null && !plan.isBlank()) {
            int[] prog = parseMilestoneProgress(plan);
            if (prog[1] > 0) phaseLabel += "  (" + prog[0] + "/" + prog[1] + " milestones complete)";
        }
        sb.append("Phase: ").append(phaseLabel).append('\n');

        // Cost/iteration budget
        if (iterations > 0 || tokenEstimate > 0) {
            String tokens = tokenEstimate > 0 ? "~" + (tokenEstimate / 1000) + "k tokens" : "tokens: ?";
            sb.append("Budget: ").append(tokens).append(" · ").append(iterations).append(" iterations\n");
        }

        // Loaded files with brief structure
        List<String> pinned = cm.getAIPinnedPaths();
        if (!pinned.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (String rel : pinned) {
                Path abs = basePath != null ? Path.of(basePath, rel) : null;
                parts.add(abs != null ? extractStructure(abs, rel) : rel);
            }
            sb.append("Loaded: ").append(String.join(" · ", parts)).append('\n');
        }

        // Evicted files
        List<String> evicted = cm.getEvictedFiles();
        if (!evicted.isEmpty()) {
            sb.append("Evicted (re-pin if needed): ").append(String.join(", ", evicted)).append('\n');
        }

        sb.append("=======================");
        return sb.toString();
    }

    private static int[] parseMilestoneProgress(String plan) {
        int total = 0, complete = 0;
        for (String line : plan.split("\n")) {
            if (line.matches("^## Milestone \\d+:.*")) total++;
            if (line.contains("**Status:** COMPLETE")) complete++;
        }
        return new int[]{complete, total};
    }

    private static final Pattern CLASS_PAT  = Pattern.compile("(?:class|interface|enum)\\s+(\\w+)");
    private static final Pattern METHOD_PAT = Pattern.compile(
            "(?:public|protected)\\s+(?:(?:static|final|abstract|synchronized)\\s+)*"
          + "(?:[\\w<>\\[\\],\\s]+\\s+)?(\\w+)\\s*\\(");
    private static final Set<String> SKIP_NAMES = Set.of(
            "class", "interface", "enum", "return", "new", "if", "while", "for", "switch");

    private static String extractStructure(Path abs, String rel) {
        String filename = Path.of(rel).getFileName().toString();
        if (!filename.endsWith(".java") && !filename.endsWith(".kt")) return filename;
        try {
            String content = Files.readString(abs, StandardCharsets.UTF_8);

            Matcher cm = CLASS_PAT.matcher(content);
            String primaryClass = cm.find() ? cm.group(1) : null;

            List<String> methods = new ArrayList<>();
            Matcher mm = METHOD_PAT.matcher(content);
            while (mm.find() && methods.size() < 5) {
                String name = mm.group(1);
                if (!SKIP_NAMES.contains(name)) methods.add(name + "()");
            }

            if (primaryClass == null && methods.isEmpty()) return filename;
            StringBuilder sb = new StringBuilder(filename).append(" [");
            if (primaryClass != null) sb.append(primaryClass);
            if (!methods.isEmpty()) sb.append(": ").append(String.join(", ", methods));
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return filename;
        }
    }

    // ── Memory store ──────────────────────────────────────────────────────────────

    public MemoryStore getMemoryStore() { return memoryStore; }
}
