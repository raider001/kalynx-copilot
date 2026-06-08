package copilot.review;

import java.util.List;

public class DiffHunk {
    public enum Status { PENDING, ACCEPTED, REJECTED }

    public final List<DiffLine> lines;
    /** 1-based start line in the original file for this hunk. */
    public final int origStart;
    /** 1-based start line in the new content for this hunk. */
    public final int newStart;

    public volatile Status status = Status.PENDING;

    public DiffHunk(List<DiffLine> lines, int origStart, int newStart) {
        this.lines     = lines;
        this.origStart = origStart;
        this.newStart  = newStart;
    }

    /** True if any line in this hunk is ADDED or REMOVED (i.e. not a pure context hunk). */
    public boolean hasChanges() {
        return lines.stream().anyMatch(l -> l.type != DiffLine.Type.CONTEXT);
    }

    public long addedCount()   { return lines.stream().filter(l -> l.type == DiffLine.Type.ADDED).count(); }
    public long removedCount() { return lines.stream().filter(l -> l.type == DiffLine.Type.REMOVED).count(); }
}
