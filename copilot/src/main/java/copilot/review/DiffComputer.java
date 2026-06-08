package copilot.review;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes a line-by-line LCS diff between two texts, then groups the result
 * into hunks with surrounding context lines.
 */
public final class DiffComputer {

    private DiffComputer() {}

    /**
     * Returns a flat ordered list of {@link DiffLine}s representing the diff
     * between {@code origText} and {@code newText}.
     */
    public static List<DiffLine> computeLines(String origText, String newText) {
        String[] orig = origText.isEmpty() ? new String[0] : origText.split("\n", -1);
        String[] mod  = newText.isEmpty()  ? new String[0] : newText.split("\n", -1);
        int n = orig.length, m = mod.length;

        // For very large files fall back to a simple "all removed then all added" diff
        if ((long) n * m > 4_000_000L) {
            List<DiffLine> fallback = new ArrayList<>(n + m);
            for (int i = 0; i < n; i++) fallback.add(new DiffLine(DiffLine.Type.REMOVED, orig[i], i + 1, -1));
            for (int j = 0; j < m; j++) fallback.add(new DiffLine(DiffLine.Type.ADDED,   mod[j],  -1,    j + 1));
            return fallback;
        }

        // Build LCS DP table
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++)
                dp[i][j] = orig[i - 1].equals(mod[j - 1])
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);

        // Iterative backtrack (avoids stack overflow on large inputs)
        List<DiffLine> result = new ArrayList<>(n + m);
        int i = n, j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && orig[i - 1].equals(mod[j - 1])) {
                result.add(0, new DiffLine(DiffLine.Type.CONTEXT, orig[i - 1], i, j));
                i--; j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                result.add(0, new DiffLine(DiffLine.Type.ADDED, mod[j - 1], -1, j));
                j--;
            } else {
                result.add(0, new DiffLine(DiffLine.Type.REMOVED, orig[i - 1], i, -1));
                i--;
            }
        }
        return result;
    }

    /**
     * Groups diff lines into hunks, each surrounded by up to {@code contextLines}
     * unchanged context lines.
     */
    public static List<DiffHunk> computeHunks(String origText, String newText, int contextLines) {
        List<DiffLine> all = computeLines(origText, newText);
        List<DiffHunk> hunks = new ArrayList<>();
        int total = all.size();
        int pos = 0;

        while (pos < total) {
            // Find next changed line
            if (all.get(pos).type == DiffLine.Type.CONTEXT) { pos++; continue; }

            // Start of a change block — expand with context
            int start = Math.max(0, pos - contextLines);
            int end   = pos;

            // Extend end past all consecutive changed lines (+context)
            while (end < total) {
                if (all.get(end).type != DiffLine.Type.CONTEXT) {
                    end++;
                } else {
                    // Context line — include up to contextLines more then stop
                    int contextEnd = end + contextLines;
                    // But peek ahead: if another change starts within contextLines, merge
                    int nextChange = end;
                    while (nextChange < Math.min(total, contextEnd + 1) &&
                           all.get(nextChange).type == DiffLine.Type.CONTEXT) nextChange++;
                    if (nextChange < total && nextChange <= contextEnd &&
                        all.get(nextChange).type != DiffLine.Type.CONTEXT) {
                        // Merge: extend to cover the next change too
                        end = nextChange + 1;
                    } else {
                        end = Math.min(total, end + contextLines);
                        break;
                    }
                }
            }
            end = Math.min(end, total);

            List<DiffLine> hunkLines = new ArrayList<>(all.subList(start, end));
            int origStart = hunkLines.stream()
                    .filter(l -> l.origLine > 0).mapToInt(l -> l.origLine).min().orElse(1);
            int newStart  = hunkLines.stream()
                    .filter(l -> l.newLine  > 0).mapToInt(l -> l.newLine).min().orElse(1);
            hunks.add(new DiffHunk(hunkLines, origStart, newStart));
            pos = end;
        }
        return hunks;
    }
}
