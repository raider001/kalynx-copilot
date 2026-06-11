package copilot.memory;

import copilot.context.ContextManager;

import java.util.*;
import java.util.stream.Collectors;

/** Tag-based semantic fact store. Tags = pinned file paths; retrieval is deterministic. */
public class MemoryStore {

    private static final int MAX_FACTS  = 100;
    private static final int MAX_RECALL = 8;

    private final ContextManager cm;

    public MemoryStore(ContextManager cm) {
        this.cm = cm;
    }

    /** Adds or updates a fact (matched by normalized text, newest-wins). Evicts oldest when full. */
    public void addOrUpdate(String text, List<String> fileTags, String phase, String source) {
        if (text == null || text.isBlank()) return;
        String norm = text.trim();

        List<MemoryFact> facts = cm.getMemoryFacts();

        for (MemoryFact f : facts) {
            if (f.text.trim().equalsIgnoreCase(norm)) {
                f.text      = norm;
                f.fileTags  = new ArrayList<>(fileTags);
                f.phase     = phase;
                f.timestamp = System.currentTimeMillis();
                f.source    = source;
                cm.saveMemoryFacts(facts);
                return;
            }
        }

        if (facts.size() >= MAX_FACTS) {
            facts.stream().min(Comparator.comparingLong(f -> f.timestamp)).ifPresent(facts::remove);
        }

        String id = UUID.randomUUID().toString().substring(0, 8);
        facts.add(new MemoryFact(id, norm, fileTags, phase, source));
        cm.saveMemoryFacts(facts);
    }

    /** Returns facts relevant to the given pinned file paths, capped at MAX_RECALL. */
    public List<MemoryFact> recall(List<String> pinnedFilePaths) {
        List<MemoryFact> facts = cm.getMemoryFacts();
        if (facts.isEmpty()) return Collections.emptyList();

        Set<String> pinned = pinnedFilePaths.stream()
                .map(p -> p.replace("\\", "/"))
                .collect(Collectors.toSet());

        List<MemoryFact> matched = new ArrayList<>();
        List<MemoryFact> general = new ArrayList<>();

        for (MemoryFact f : facts) {
            if (f.fileTags == null || f.fileTags.isEmpty()) {
                general.add(f);
            } else {
                boolean relevant = f.fileTags.stream()
                        .anyMatch(tag -> pinned.contains(tag.replace("\\", "/")));
                if (relevant) matched.add(f);
            }
        }

        Comparator<MemoryFact> byRecency = Comparator.comparingLong((MemoryFact f) -> f.timestamp).reversed();
        matched.sort(byRecency);
        general.sort(byRecency);

        List<MemoryFact> result = new ArrayList<>(matched);
        for (MemoryFact f : general) {
            if (result.size() >= MAX_RECALL) break;
            result.add(f);
        }
        if (result.size() > MAX_RECALL) result = result.subList(0, MAX_RECALL);
        return result;
    }

    /** Builds a context block injected into the system message when facts are available. */
    public String buildMemoryBlock(List<String> pinnedFilePaths) {
        List<MemoryFact> recalled = recall(pinnedFilePaths);
        if (recalled.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("# Project Memory\n");
        sb.append("Facts from past sessions (verify code-specific claims before acting):\n\n");
        for (MemoryFact f : recalled) {
            sb.append("- ").append(f.text);
            if (f.fileTags != null && !f.fileTags.isEmpty())
                sb.append(" *(").append(String.join(", ", f.fileTags)).append(")*");
            sb.append('\n');
        }
        return sb.toString();
    }
}
