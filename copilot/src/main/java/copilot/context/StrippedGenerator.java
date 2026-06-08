package copilot.context;

import java.util.Set;

/**
 * Entry point for generating stripped file representations.
 * Picks the appropriate {@link LanguageStripper} based on file extension
 * and prepends a small header so the agent knows what it's reading.
 */
public class StrippedGenerator {

    private static final BraceLanguageStripper BRACE  = new BraceLanguageStripper();
    private static final PythonStripper         PYTHON = new PythonStripper();

    private static final Set<String> BRACE_EXTS = Set.of(
            "java", "kt", "kts",
            "cs",
            "js", "jsx", "ts", "tsx",
            "go",
            "rs",
            "swift",
            "cpp", "cxx", "cc", "c", "h", "hpp", "hxx",
            "scala", "groovy"
    );

    /**
     * Generates a stripped representation of {@code content}.
     *
     * @param content  full source file content
     * @param fileName file name (used for extension detection and the header)
     * @return stripped content with a small informational header
     */
    public static String generate(String content, String fileName) {
        String ext  = extension(fileName).toLowerCase();
        String body;

        if ("py".equals(ext)) {
            body = PYTHON.strip(content, fileName);
        } else if (BRACE_EXTS.contains(ext)) {
            body = BRACE.strip(content, fileName);
        } else {
            // Unknown / markup / config — return as-is so it's still useful
            body = content;
        }

        return "// Kalynx Context — stripped: " + fileName + "\n"
             + "// Public API signatures only. Use read_file for full implementation.\n\n"
             + body;
    }

    /** Returns the language tag for Markdown fenced code blocks. */
    public static String langTag(String fileName) {
        return switch (extension(fileName).toLowerCase()) {
            case "java"                          -> "java";
            case "kt", "kts"                     -> "kotlin";
            case "cs"                            -> "csharp";
            case "ts", "tsx"                     -> "typescript";
            case "js", "jsx"                     -> "javascript";
            case "py"                            -> "python";
            case "go"                            -> "go";
            case "rs"                            -> "rust";
            case "swift"                         -> "swift";
            case "cpp", "cxx", "cc", "hpp", "hxx" -> "cpp";
            case "c", "h"                        -> "c";
            case "scala"                         -> "scala";
            case "groovy"                        -> "groovy";
            default                              -> "";
        };
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }
}
