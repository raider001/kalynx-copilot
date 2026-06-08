package copilot.review;

import java.util.List;

/** A file modification staged by an agent tool, waiting for user accept/reject. */
public class PendingChange {

    public enum ChangeType { MODIFY, CREATE }

    public final String relativePath;
    public final String absolutePath;
    /** Null for CREATE changes (file did not exist before). */
    public final String originalContent;
    public final String newContent;
    public final ChangeType changeType;

    /** Computed hunks — populated lazily on first access. */
    private List<DiffHunk> hunks;

    public PendingChange(String relativePath, String absolutePath,
                         String originalContent, String newContent, ChangeType changeType) {
        this.relativePath  = relativePath;
        this.absolutePath  = absolutePath;
        this.originalContent = originalContent;
        this.newContent    = newContent;
        this.changeType    = changeType;
    }

    public String fileName() {
        return new java.io.File(relativePath).getName();
    }

    public synchronized List<DiffHunk> getHunks() {
        if (hunks == null) {
            String orig = originalContent != null ? originalContent : "";
            hunks = DiffComputer.computeHunks(orig, newContent, 3);
        }
        return hunks;
    }

    /** True when every hunk has been resolved (accepted or rejected). */
    public boolean isFullyResolved() {
        return getHunks().stream().allMatch(h -> h.status != DiffHunk.Status.PENDING);
    }

    /**
     * Builds the final file content by applying accepted hunks and restoring
     * original lines for rejected hunks.  PENDING hunks are treated as accepted.
     */
    public String buildResolvedContent() {
        List<DiffHunk> hunkList = getHunks();
        StringBuilder sb = new StringBuilder();
        for (DiffHunk hunk : hunkList) {
            boolean reject = hunk.status == DiffHunk.Status.REJECTED;
            for (DiffLine line : hunk.lines) {
                switch (line.type) {
                    case CONTEXT -> sb.append(line.text).append('\n');
                    case ADDED   -> { if (!reject) sb.append(line.text).append('\n'); }
                    case REMOVED -> { if (reject)  sb.append(line.text).append('\n'); }
                }
            }
        }
        // Trim trailing extra newline added by the loop
        String result = sb.toString();
        if (result.endsWith("\n") && !newContent.endsWith("\n"))
            result = result.substring(0, result.length() - 1);
        return result;
    }
}
