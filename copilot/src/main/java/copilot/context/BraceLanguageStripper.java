package copilot.context;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Strips brace-delimited languages down to public API signatures.
 *
 * Supported: Java, Kotlin, C#, TypeScript, JavaScript, Go, Rust, Swift, C, C++.
 *
 * Strategy:
 *  - Depth 0 (outside any class/struct): output everything.
 *  - Depth 1 (class/struct body): emit public member signatures; replace bodies with ';'.
 *  - Depth ≥ 2 (inside a method body): skip entirely.
 *
 * Visibility rules are language-specific — see isVisible().
 */
public class BraceLanguageStripper implements LanguageStripper {

    @Override
    public String strip(String content, String fileName) {
        String ext = extension(fileName).toLowerCase();
        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder();

        int depth = 0;
        // C++ tracks access-specifier sections (public:/private:/protected:)
        boolean cppPublicSection = false; // default struct = public, class = private

        List<String> commentBuf  = new ArrayList<>(); // doc-comments / annotations waiting for a member
        List<String> sigBuf      = new ArrayList<>(); // lines of the current member signature
        boolean      sigVisible  = false;             // whether the current member should be emitted

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            String cleaned = stripStringsAndLineComments(rawLine);
            int    bal     = braceBalance(cleaned);

            // ── DEPTH 0: top-level scope ──────────────────────────────────────────────
            if (depth == 0) {
                // For Go and Rust, filter top-level function/impl visibility here.
                if (("go".equals(ext) || "rs".equals(ext)) && isTopLevelFunction(trimmed, ext)) {
                    if (!isVisible(trimmed, ext, false)) {
                        // Skip private function body
                        if (bal > 0) depth += bal; // start skipping
                        continue;
                    }
                    // Emit public function signature only (body to be skipped)
                    if (bal > 0) {
                        out.append(buildSignature(List.of(rawLine))).append('\n');
                        depth += bal;
                        continue;
                    }
                }
                out.append(rawLine).append('\n');
                depth += bal;

            // ── DEPTH 1: class / struct body ─────────────────────────────────────────
            } else if (depth == 1) {

                // C++ access specifiers — update section
                if (("cpp".equals(ext) || "cxx".equals(ext) || "cc".equals(ext)
                        || "c".equals(ext) || "h".equals(ext) || "hpp".equals(ext))) {
                    if (trimmed.equals("public:"))    { cppPublicSection = true;  commentBuf.clear(); continue; }
                    if (trimmed.equals("private:"))   { cppPublicSection = false; commentBuf.clear(); continue; }
                    if (trimmed.equals("protected:")) { cppPublicSection = false; commentBuf.clear(); continue; }
                }

                // Blank line — reset comment buffer, add spacing
                if (trimmed.isEmpty()) {
                    commentBuf.clear();
                    if (sigBuf.isEmpty()) out.append('\n');
                    continue;
                }

                // Comment or annotation line — buffer it
                if (isCommentOrAnnotation(trimmed)) {
                    commentBuf.add(rawLine);
                    continue;
                }

                // Closing brace — end of class/struct
                if (bal < 0 && (depth + bal) == 0) {
                    flushSig(out, sigBuf, sigVisible);
                    sigBuf.clear(); commentBuf.clear(); sigVisible = false;
                    out.append(rawLine).append('\n');
                    depth = 0;
                    continue;
                }

                // Starting a new member (sigBuf empty) or continuing a multi-line signature
                if (sigBuf.isEmpty()) {
                    boolean cpp = "cpp".equals(ext) || "cxx".equals(ext) || "cc".equals(ext)
                            || "c".equals(ext) || "h".equals(ext) || "hpp".equals(ext);
                    sigVisible = cpp ? cppPublicSection : isVisible(trimmed, ext, false);
                    if (sigVisible) sigBuf.addAll(commentBuf);
                    commentBuf.clear();
                }
                sigBuf.add(rawLine);

                boolean endsWithSemi     = cleaned.trim().endsWith(";");
                boolean hasSingleLineBod = !endsWithSemi && cleaned.contains("{") && cleaned.contains("}") && bal == 0;

                if (bal > 0) {
                    // Method / block body opens
                    if (sigVisible) out.append(buildSignature(sigBuf)).append('\n');
                    sigBuf.clear(); sigVisible = false;
                    depth += bal;

                } else if (endsWithSemi || hasSingleLineBod) {
                    // Field, abstract/interface method, or inline `foo() { }`
                    if (sigVisible) {
                        if (hasSingleLineBod) {
                            out.append(buildSignature(sigBuf)).append('\n');
                        } else {
                            for (String s : sigBuf) out.append(s).append('\n');
                        }
                    }
                    sigBuf.clear(); sigVisible = false;
                }
                // else: still collecting multi-line signature — do nothing

            // ── DEPTH ≥ 2: inside a method body — skip ───────────────────────────────
            } else {
                depth += bal;
                if (depth == 1) {
                    // Returned to class body — reset accumulators
                    sigBuf.clear(); commentBuf.clear(); sigVisible = false;
                } else if (depth == 0) {
                    // Popped back to top level (e.g. closing the class from inside a body — shouldn't
                    // happen with well-formed code, but handle gracefully)
                    sigBuf.clear(); commentBuf.clear(); sigVisible = false;
                }
            }
        }

        // Collapse runs of 3+ newlines (source had multiple blank lines between members) to one blank line
        return out.toString().stripTrailing().replaceAll("\n{3,}", "\n\n") + "\n";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /** True if the line begins a comment, block-comment continuation, or an annotation. */
    private boolean isCommentOrAnnotation(String trimmed) {
        return trimmed.startsWith("//")
                || trimmed.startsWith("*")
                || trimmed.startsWith("/*")
                || trimmed.startsWith("@");
    }

    /**
     * True if the member should appear in the stripped output.
     * Rules are deliberately permissive: include anything not explicitly private.
     */
    private boolean isVisible(String trimmed, String ext, boolean insideCppPublic) {
        switch (ext) {
            case "java": case "cs": case "swift":
                // Explicit public/protected, or no visibility modifier (interface default)
                return !hasWord(trimmed, "private");

            case "kt": case "kts":
                return !hasWord(trimmed, "private") && !hasWord(trimmed, "internal");

            case "ts": case "tsx": case "js": case "jsx":
                return !hasWord(trimmed, "private") && !trimmed.startsWith("#");

            case "rs":
                // Rust: must have `pub` keyword
                return hasWord(trimmed, "pub");

            case "go":
                // Go: public if the identifier starts with an uppercase letter
                return isGoPublic(trimmed);

            default:
                return true; // unknown language — include everything
        }
    }

    /** Extracts the declared identifier for Go and checks capitalisation. */
    private boolean isGoPublic(String trimmed) {
        // func (recv Type) Name(...) or func Name(...)
        // type Name struct / var Name / const Name
        Pattern p = Pattern.compile(
                "(?:func\\s+(?:\\([^)]*\\)\\s+))?(\\w+)\\s*[({]?");
        java.util.regex.Matcher m = p.matcher(trimmed);
        if (m.find()) {
            String name = m.group(1);
            return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
        }
        return false;
    }

    /** True if the line is a top-level function declaration (for Go/Rust filtering). */
    private boolean isTopLevelFunction(String trimmed, String ext) {
        if ("go".equals(ext))  return trimmed.startsWith("func ");
        if ("rs".equals(ext))  return trimmed.startsWith("fn ")   || trimmed.startsWith("pub fn ");
        return false;
    }

    /** Net change in brace depth for this (strings-and-comments-stripped) line. */
    private int braceBalance(String cleaned) {
        int bal = 0;
        for (char c : cleaned.toCharArray()) {
            if (c == '{') bal++;
            else if (c == '}') bal--;
        }
        return bal;
    }

    /**
     * Strips double-quoted strings, single-quoted chars, and // line comments
     * before brace counting so string contents don't confuse the depth tracker.
     */
    private String stripStringsAndLineComments(String line) {
        // Remove // comments first (outside strings — approximation good enough for brace counting)
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        boolean inChar   = false;
        char prev = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!inString && !inChar && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') break;
            if (!inChar  && c == '"' && prev != '\\') inString = !inString;
            if (!inString && c == '\'' && prev != '\\') inChar = !inChar;
            if (!inString && !inChar) sb.append(c);
            prev = c;
        }
        return sb.toString();
    }

    /** Joins signature lines and replaces the opening `{` (and everything after) with `;`. */
    private String buildSignature(List<String> sigLines) {
        StringBuilder sb = new StringBuilder();
        for (String l : sigLines) sb.append(l).append('\n');
        String joined = sb.toString().stripTrailing();
        int lastBrace = joined.lastIndexOf('{');
        if (lastBrace >= 0) joined = joined.substring(0, lastBrace).stripTrailing() + ";";
        return joined;
    }

    /** Outputs any accumulated signature to the result buffer if visible. */
    private void flushSig(StringBuilder out, List<String> sigBuf, boolean sigVisible) {
        if (sigVisible && !sigBuf.isEmpty()) {
            for (String s : sigBuf) out.append(s).append('\n');
        }
    }

    /** True if the word appears as a whole token in the line. */
    private boolean hasWord(String line, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(line).find();
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }
}
