package copilot.tools.api;

import java.nio.file.Path;

/**
 * Whitelist guard for all file tool operations.
 *
 * The only paths that are allowed are those that resolve to a location
 * inside the project root. Everything else — {@code ../} traversal,
 * absolute paths, UNC paths, URL-encoded sequences — is rejected because
 * it doesn't start with the project base after normalisation.
 *
 * All file tools (create_file, replace_in_file, add_to_context, list_files)
 * must call {@link #check} or {@link #resolve} before touching the filesystem.
 */
public final class PathGuard {

    private PathGuard() {}

    /**
     * Resolves {@code userPath} against the project {@code basePath}, normalises
     * it, then checks it against the whitelist (must start with basePath).
     *
     * @return the resolved, normalised path if it is inside the project
     * @throws SecurityException if the path is not within the project root
     */
    public static Path resolve(String basePath, String userPath) {
        Path base     = Path.of(basePath).normalize().toAbsolutePath();
        Path resolved = base.resolve(userPath).normalize().toAbsolutePath();
        if (!resolved.startsWith(base)) {
            throw new SecurityException(
                    "Access denied — path is outside the project root. " +
                    "Only files within the project may be accessed. Attempted: " + userPath);
        }
        return resolved;
    }

    /**
     * Returns an error string if the path is outside the project root, or
     * {@code null} if it is on the whitelist. Prefer this in {@code execute()}
     * methods that return a String rather than throw.
     */
    public static String check(String basePath, String userPath) {
        try {
            resolve(basePath, userPath);
            return null;
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        }
    }
}
