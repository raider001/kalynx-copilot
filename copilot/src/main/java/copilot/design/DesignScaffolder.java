package copilot.design;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Manages two distinct sets of files:
 *
 * <ol>
 *   <li><b>Design docs</b> — scaffolded into {@code .kalynx-context/design/} inside the project.
 *       These are the user's actual documentation and are project-specific.</li>
 *   <li><b>File templates</b> — scaffolded into the IDE's global config directory under
 *       {@code kalynx-copilot/templates/}. These are plugin configuration, global across
 *       all projects, and user-editable. Bundled defaults are used as fallback.</li>
 * </ol>
 *
 * Neither set overwrites files that already exist — user edits are always preserved.
 */
public final class DesignScaffolder {

    public static final String DESIGN_DIR     = ".kalynx-context/design";
    public static final String PLUGIN_DIR     = "kalynx-copilot";
    public static final String TEMPLATES_DIR  = "kalynx-copilot/templates";

    private static final String RESOURCE_ROOT = "/copilot/design/templates";

    /** Design doc stubs scaffolded into the project (documentation, not templates). */
    private static final List<String> DESIGN_DOCS = List.of(
            "readme.md",
            "objectives.md",
            "requirements.md",
            "conventions/readme.md",
            "interfaces/readme.md",
            "highlevel/readme.md",
            "workflows/readme.md",
            "storage/readme.md",
            "storage/database.md",
            "storage/filesystem.md",
            "configuration/readme.md",
            "api/readme.md",
            "decisions/readme.md"
    );

    /** Per-section file templates scaffolded into the global plugin config directory. */
    private static final List<String> FILE_TEMPLATES = List.of(
            "interfaces/_template.md",
            "workflows/_template.md",
            "decisions/_template.md"
    );

    private DesignScaffolder() {}

    /**
     * Ensures the project design docs exist and the global templates are in place.
     * Runs on a background thread — safe to call from the EDT.
     */
    public static void scaffoldIfNeeded(Project project) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            scaffoldDesignDocs(project);
            scaffoldPluginTemplates();
        });
    }

    /** Returns true if the project design directory has been scaffolded. */
    public static boolean isScaffolded(Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) return false;
        return Files.exists(Path.of(basePath, DESIGN_DIR, "readme.md"));
    }

    /**
     * Returns the content for a per-section file template (e.g. {@code "interfaces/_template.md"}).
     * Checks the global plugin config directory first; falls back to the bundled resource.
     */
    public static String loadFileTemplate(String relative) {
        Path override = pluginTemplatesRoot().resolve(relative);
        if (Files.exists(override)) {
            try { return Files.readString(override, StandardCharsets.UTF_8); }
            catch (IOException ignored) {}
        }
        return loadResource(relative);
    }

    // ------------------------------------------------------------------

    private static void scaffoldDesignDocs(Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) return;
        Path designRoot = Path.of(basePath, DESIGN_DIR);
        for (String relative : DESIGN_DOCS) {
            writeIfAbsent(designRoot.resolve(relative), loadResource(relative));
        }
    }

    private static void scaffoldPluginTemplates() {
        Path templatesRoot = pluginTemplatesRoot();
        for (String relative : FILE_TEMPLATES) {
            writeIfAbsent(templatesRoot.resolve(relative), loadResource(relative));
        }
    }

    private static Path pluginTemplatesRoot() {
        return Path.of(PathManager.getConfigPath(), TEMPLATES_DIR);
    }

    private static void writeIfAbsent(Path target, String content) {
        if (content == null || Files.exists(target)) return;
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    private static String loadResource(String relative) {
        String path = RESOURCE_ROOT + "/" + relative;
        try (InputStream is = DesignScaffolder.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
