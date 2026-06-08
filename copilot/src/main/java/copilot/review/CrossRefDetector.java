package copilot.review;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects cross-references to symbols introduced by a diff hunk.
 * When a hunk is rejected the caller can ask: "what else refers to the
 * symbols this hunk would have added?" — so the user knows what else to fix.
 */
public final class CrossRefDetector {

    private CrossRefDetector() {}

    // Patterns that identify new symbols in added lines
    private static final List<Pattern> SYMBOL_PATTERNS = List.of(
            // Java/Kotlin method declarations
            Pattern.compile("(?:public|private|protected|internal|fun|def)\\s+\\S+\\s+(\\w{2,})\\s*\\("),
            // Class / interface / enum / record / object declarations
            Pattern.compile("(?:class|interface|enum|record|object)\\s+(\\w{2,})"),
            // Top-level functions without visibility modifier (Kotlin / Python style)
            Pattern.compile("^\\s*(?:fun|def|function)\\s+(\\w{2,})\\s*\\(", Pattern.MULTILINE)
    );

    /**
     * Extracts symbol names that were <em>added</em> by {@code hunk} and searches
     * for usages across:
     * <ol>
     *   <li>Other pending changes (their staged new content)</li>
     *   <li>Existing project source files</li>
     * </ol>
     *
     * @return Map from symbol name → list of "RelativePath:lineNum" reference strings.
     */
    public static Map<String, List<String>> findReferences(
            DiffHunk hunk,
            PendingChange sourceChange,
            List<PendingChange> allPendingChanges,
            Project project) {

        Set<String> symbols = extractSymbols(hunk);
        if (symbols.isEmpty()) return Collections.emptyMap();

        Map<String, List<String>> refs = new LinkedHashMap<>();
        String basePath = project.getBasePath();

        // Search other pending changes
        for (PendingChange other : allPendingChanges) {
            if (other == sourceChange) continue;
            String content = other.newContent;
            searchIn(symbols, content, other.relativePath, refs);
        }

        // Search existing project source files (limit to avoid hangs on huge projects)
        if (basePath != null) {
            VirtualFile root = VfsUtil.findFileByIoFile(new java.io.File(basePath), false);
            if (root != null) {
                searchVfs(root, symbols, basePath, allPendingChanges, refs, new int[]{0});
            }
        }

        return refs;
    }

    private static Set<String> extractSymbols(DiffHunk hunk) {
        Set<String> symbols = new LinkedHashSet<>();
        for (DiffLine line : hunk.lines) {
            if (line.type != DiffLine.Type.ADDED) continue;
            for (Pattern p : SYMBOL_PATTERNS) {
                Matcher m = p.matcher(line.text);
                while (m.find()) symbols.add(m.group(1));
            }
        }
        return symbols;
    }

    private static void searchIn(Set<String> symbols, String content, String label,
                                  Map<String, List<String>> out) {
        if (content == null) return;
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            for (String sym : symbols) {
                if (line.contains(sym)) {
                    out.computeIfAbsent(sym, k -> new ArrayList<>())
                       .add(label + ":" + (i + 1));
                }
            }
        }
    }

    private static void searchVfs(VirtualFile dir, Set<String> symbols, String basePath,
                                   List<PendingChange> pending,
                                   Map<String, List<String>> out, int[] count) {
        if (count[0] > 300) return; // cap to prevent slowness
        for (VirtualFile child : dir.getChildren()) {
            if (child.isDirectory()) {
                if (!child.getName().startsWith(".") &&
                    !child.getName().equals("build") &&
                    !child.getName().equals("target") &&
                    !child.getName().equals("node_modules")) {
                    searchVfs(child, symbols, basePath, pending, out, count);
                }
            } else {
                String ext = child.getExtension();
                if (ext == null) continue;
                if (!Set.of("java","kt","py","ts","tsx","js","jsx","cs","go","rs","cpp","c","h").contains(ext)) continue;
                // Skip files that already have a pending change (already searched above)
                String rel = child.getPath().substring(
                        Math.min(basePath.length() + 1, child.getPath().length()));
                boolean hasPending = pending.stream().anyMatch(p -> p.relativePath.equals(rel));
                if (hasPending) continue;
                count[0]++;
                try {
                    String content = new String(child.contentsToByteArray(), child.getCharset());
                    searchIn(symbols, content, rel, out);
                } catch (Exception ignored) {}
            }
        }
    }
}
