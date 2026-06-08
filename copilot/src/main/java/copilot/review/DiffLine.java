package copilot.review;

public class DiffLine {
    public enum Type { CONTEXT, ADDED, REMOVED }

    public final Type   type;
    public final String text;
    /** 1-based line number in the original file; -1 for ADDED lines. */
    public final int origLine;
    /** 1-based line number in the new content; -1 for REMOVED lines. */
    public final int newLine;

    public DiffLine(Type type, String text, int origLine, int newLine) {
        this.type     = type;
        this.text     = text;
        this.origLine = origLine;
        this.newLine  = newLine;
    }
}
