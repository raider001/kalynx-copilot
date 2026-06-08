package copilot.context;

/**
 * Strips Python files to public class/function signatures.
 *
 * Strategy (indentation-based, no brace counting):
 *  - Indent level 0: output everything (imports, top-level class/def).
 *  - Indent level 1 (class body): output public member defs and class variables; skip private.
 *  - Deeper indent (method body): skip, but capture the first docstring line.
 *
 * "Public" in Python: name does not start with a single underscore.
 * Dunder methods (__init__, __str__, etc.) are always kept.
 */
public class PythonStripper implements LanguageStripper {

    @Override
    public String strip(String content, String fileName) {
        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder();

        boolean insideDef      = false; // we're skipping a method body
        int     defIndent      = -1;    // indent level of the def line we're skipping
        boolean wantDocstring  = false; // next non-blank line might be a docstring to capture

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            int indent = indentWidth(rawLine);

            // Blank and comment lines
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (!insideDef) out.append(rawLine).append('\n');
                continue;
            }

            // We're inside a skipped method body
            if (insideDef) {
                if (indent > defIndent) {
                    // Still inside the body
                    if (wantDocstring && (trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''"))) {
                        // Capture opening docstring line
                        out.append(rawLine).append('\n');
                        // If the docstring closes on the same line (single-line """) mark done
                        String inner = trimmed.substring(3);
                        if (inner.endsWith("\"\"\"") || inner.endsWith("'''")) {
                            wantDocstring = false;
                        }
                        // Multi-line docstring bodies are still skipped
                    }
                    wantDocstring = false;
                    continue;
                }
                // Exited the method body
                insideDef = false;
                wantDocstring = false;
            }

            // Not inside a skipped body — normal processing
            if (trimmed.startsWith("def ") || trimmed.startsWith("async def ")) {
                String name = extractName(trimmed);
                if (isPublicPython(name)) {
                    out.append(rawLine).append('\n');
                    insideDef = true;
                    defIndent = indent;
                    wantDocstring = true;
                } else {
                    // Private def — skip it and its body silently
                    insideDef = true;
                    defIndent = indent;
                    wantDocstring = false;
                }
            } else {
                // class declaration, import, class-level variable, decorator, etc.
                out.append(rawLine).append('\n');
            }
        }

        return out.toString().stripTrailing().replaceAll("\n{3,}", "\n\n") + "\n";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private int indentWidth(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return i;
    }

    /** Extracts the bare function/method name from a def or class line. */
    private String extractName(String trimmed) {
        // "def foo(..." or "async def foo(..." or "class Foo(..."
        int start = trimmed.startsWith("async ") ? trimmed.indexOf("def ") + 4
                  : trimmed.indexOf(' ') + 1;
        int end = trimmed.indexOf('(', start);
        if (end < 0) end = trimmed.indexOf(':', start);
        if (end < 0) end = trimmed.length();
        return trimmed.substring(start, end).trim();
    }

    /**
     * Public in Python: not prefixed with a single underscore.
     * Dunder methods (__init__, __str__, etc.) are always included.
     */
    private boolean isPublicPython(String name) {
        if (name.startsWith("__") && name.endsWith("__")) return true; // dunder — always public
        return !name.startsWith("_");
    }
}
