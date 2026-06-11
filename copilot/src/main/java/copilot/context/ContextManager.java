package copilot.context;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import copilot.agent.Phase;
import copilot.agent.PhaseController;
import copilot.memory.MemoryFact;
import copilot.tools.api.PathGuard;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Project-level service that manages the user-curated context file set.
 *
 * Files are added via the chat panel's drag-and-drop.  For each watched file or
 * folder a stripped snapshot is written to {@code .kalynx-context/stripped/}.
 * A {@link BulkFileListener} regenerates snapshots whenever source files change.
 *
 * All file I/O uses Java NIO directly to avoid IntelliJ VFS read-action requirements
 * on background threads.
 */
@State(name = "KalynxContextManager", storages = @Storage("kalynxContext.xml"))
public class ContextManager implements PersistentStateComponent<ContextManager.State>, Disposable {

    private static final String SNAPSHOT_DIR = ".kalynx-context/dynamic-context/stripped";

    private final Project project;
    private final List<WatchedEntry> entries   = new CopyOnWriteArrayList<>();
    private final List<Runnable>     listeners = new CopyOnWriteArrayList<>();
    private final MessageBusConnection busConn;

    // ── Phase state instance fields (loaded from / saved to State) ────────────────
    private String  currentPhaseStr          = "ANALYSE";
    private boolean lastVerifyClean          = false;
    private int     consecutiveFailedVerifies = 0;
    private int     lastErrorCount           = -1;
    private boolean hasEditedThisPhase       = false;
    private boolean hasReadFileThisPhase     = false;
    private final List<String>     evictedFiles = new CopyOnWriteArrayList<>();
    private String  lastVerifyOutput         = "";

    // ── Semantic memory instance fields ───────────────────────────────────────────
    private final List<MemoryFact> memoryFacts = new CopyOnWriteArrayList<>();

    // ── Model scratchpad (survives compression) ───────────────────────────────────
    private String scratchpadNote = null;

    // ── PhaseController (lazy singleton) ─────────────────────────────────────────
    private volatile PhaseController phaseController = null;

    // ── Inner data classes ────────────────────────────────────────────────────────

    public enum Source { USER, AI }

    public static class WatchedEntry {
        public String  relativePath = "";
        public boolean isFolder     = false;
        public Source  source       = Source.USER;

        public WatchedEntry() {}
        public WatchedEntry(String relativePath, boolean isFolder) {
            this(relativePath, isFolder, Source.USER);
        }
        public WatchedEntry(String relativePath, boolean isFolder, Source source) {
            this.relativePath = relativePath;
            this.isFolder     = isFolder;
            this.source       = source;
        }

        public String displayName() {
            return new File(relativePath).getName() + (isFolder ? "/" : "");
        }

        @Override public String toString() { return displayName(); }
    }

    public static class State {
        public List<WatchedEntry> entries = new ArrayList<>();
        // Phase state (persisted across IDE restarts)
        public String  currentPhase              = "ANALYSE";
        public boolean lastVerifyClean           = false;
        public int     consecutiveFailedVerifies = 0;
        public int     lastErrorCount            = -1;
        public boolean hasEditedThisPhase        = false;
        public boolean hasReadFileThisPhase      = false;
        public List<String> evictedFiles         = new ArrayList<>();
        public String  lastVerifyOutput          = "";
        // Semantic memory
        public List<MemoryFact> memoryFacts      = new ArrayList<>();
        // Model scratchpad
        public String  scratchpadNote            = null;
    }

    // ── Construction ─────────────────────────────────────────────────────────────

    public ContextManager(Project project) {
        this.project = project;

        busConn = project.getMessageBus().connect(this);
        busConn.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    VirtualFile vf = event.getFile();
                    if (vf != null && !vf.isDirectory() && isWatched(vf.getPath())) {
                        regenerateSnapshot(vf.getPath());
                    }
                }
            }
        });
    }

    public static ContextManager getInstance(Project project) {
        return project.getService(ContextManager.class);
    }

    // ── PersistentStateComponent ──────────────────────────────────────────────────

    @Override
    public @Nullable State getState() {
        State s = new State();
        s.entries                 = new ArrayList<>(entries);
        s.currentPhase            = currentPhaseStr;
        s.lastVerifyClean          = lastVerifyClean;
        s.consecutiveFailedVerifies = consecutiveFailedVerifies;
        s.lastErrorCount           = lastErrorCount;
        s.hasEditedThisPhase       = hasEditedThisPhase;
        s.hasReadFileThisPhase     = hasReadFileThisPhase;
        s.evictedFiles             = new ArrayList<>(evictedFiles);
        s.lastVerifyOutput         = lastVerifyOutput;
        s.memoryFacts              = new ArrayList<>(memoryFacts);
        s.scratchpadNote           = scratchpadNote;
        return s;
    }

    @Override
    public void loadState(@NotNull State state) {
        entries.clear();
        if (state.entries != null) entries.addAll(state.entries);

        currentPhaseStr           = state.currentPhase != null ? state.currentPhase : "ANALYSE";
        lastVerifyClean            = state.lastVerifyClean;
        consecutiveFailedVerifies  = state.consecutiveFailedVerifies;
        lastErrorCount             = state.lastErrorCount;
        hasEditedThisPhase         = state.hasEditedThisPhase;
        hasReadFileThisPhase       = state.hasReadFileThisPhase;
        evictedFiles.clear();
        if (state.evictedFiles != null) evictedFiles.addAll(state.evictedFiles);
        lastVerifyOutput           = state.lastVerifyOutput != null ? state.lastVerifyOutput : "";
        memoryFacts.clear();
        if (state.memoryFacts != null) memoryFacts.addAll(state.memoryFacts);
        scratchpadNote             = state.scratchpadNote;

        // Regenerate snapshots in background — source files may have changed while IDE was closed
        ApplicationManager.getApplication().executeOnPooledThread(this::regenerateAll);
    }

    // ── Public API ────────────────────────────────────────────────────────────────

    /** Adds a file or folder dropped from the chat panel (java.io.File). */
    public void addEntry(File f) {
        addEntry(f.getAbsolutePath(), f.isDirectory());
    }

    /** Adds a file or folder from a VirtualFile reference. */
    public void addEntry(VirtualFile vf) {
        addEntry(vf.getPath(), vf.isDirectory());
    }

    private void addEntry(String absPath, boolean isDirectory) {
        String basePath = project.getBasePath();
        if (basePath == null) return;

        String rel = relativize(basePath, absPath);
        if (entries.stream().anyMatch(e -> e.relativePath.equals(rel))) return;

        WatchedEntry entry = new WatchedEntry(rel, isDirectory);
        entries.add(entry);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            generateForPath(entry, Path.of(absPath));
            notifyListeners();
        });
    }

    /** Removes an entry and deletes its snapshot file(s). */
    public void removeEntry(WatchedEntry entry) {
        entries.remove(entry);
        deleteSnapshot(entry);
        notifyListeners();
    }

    public List<WatchedEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public void addListener(Runnable listener) { listeners.add(listener); }

    /**
     * Builds a context block for injection into the agent's system prompt.
     *
     * <ul>
     *   <li>AI-pinned entries ({@link Source#AI}) → full file content, read live from disk.</li>
     *   <li>User-added entries ({@link Source#USER}) → stripped (public-signature) snapshots,
     *       skipped if the same path is already covered by an AI full-content pin.</li>
     * </ul>
     */
    public String buildContextBlock() {
        String basePath = project.getBasePath();
        if (basePath == null || entries.isEmpty()) return "";

        // Collect paths already covered by AI full-content pins so USER stripped
        // snapshots for the same file are not duplicated.
        Set<String> aiPinnedPaths = new HashSet<>();
        for (WatchedEntry e : entries) {
            if (e.source == Source.AI) aiPinnedPaths.add(e.relativePath.replace("\\", "/"));
        }

        StringBuilder sb = new StringBuilder();

        // AI-pinned: full file content read live
        for (WatchedEntry entry : entries) {
            if (entry.source != Source.AI) continue;
            Path abs = Path.of(basePath, entry.relativePath);
            appendFullContent(abs, entry.relativePath, sb);
        }

        // User-added: stripped snapshots, skipping any superseded by an AI pin
        for (WatchedEntry entry : entries) {
            if (entry.source != Source.USER) continue;
            if (aiPinnedPaths.contains(entry.relativePath.replace("\\", "/"))) continue;
            Path snapshotRoot = Path.of(basePath, SNAPSHOT_DIR, entry.relativePath);
            collectSnapshots(snapshotRoot, entry.relativePath, sb);
        }

        if (sb.isEmpty()) return "";

        return "# Dynamic Context\n"
             + "AI-pinned files appear below in full (you added these via add_to_context). "
             + "User-selected files appear as stripped public-signature snapshots. "
             + "Use remove_from_context to unpin files you no longer need.\n\n"
             + sb;
    }

    /**
     * Called by the AI {@code add_to_context} tool.
     * Pins the file or folder in full — content is read live from disk and injected
     * into the system prompt for every subsequent turn. No stripped snapshot is needed.
     * {@code relativePath} is relative to the project root (e.g. {@code "src/main/Foo.java"}).
     */
    public String addEntryForAI(String relativePath) {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: no project base path";

        String guardErr = PathGuard.check(basePath, relativePath);
        if (guardErr != null) return guardErr;

        String rel = relativePath.replace("\\", "/");

        Path abs = Path.of(basePath, rel);
        if (!Files.exists(abs)) return "Error: path not found: " + relativePath;

        // If already pinned by AI, remove the old entry and re-add so the result always
        // reads "Pinned" rather than "Already pinned". This prevents the AI from getting
        // stuck in a read_file → "Already pinned" → retry loop.
        entries.removeIf(e -> e.source == Source.AI
                && e.relativePath.replace("\\", "/").equals(rel));

        boolean isDir = Files.isDirectory(abs);
        WatchedEntry entry = new WatchedEntry(rel, isDir, Source.AI);
        entries.add(entry);
        ApplicationManager.getApplication().executeOnPooledThread(this::notifyListeners);

        getPhaseController().notifyFileRead();
        String result = "Pinned: " + relativePath + " — full content is now in your dynamic context.";

        // Warn immediately if another pinned file shares the same filename — this is the
        // most common cause of cross-file confusion when constructing old_code.
        if (!isDir) {
            String filename = Path.of(rel).getFileName().toString();
            List<String> collisions = entries.stream()
                    .filter(e -> !e.isFolder && !e.relativePath.replace("\\", "/").equals(rel))
                    .map(e -> e.relativePath)
                    .filter(p -> Path.of(p).getFileName().toString().equals(filename))
                    .toList();
            if (!collisions.isEmpty()) {
                result += "\nDISAMBIGUATION WARNING: '" + filename + "' is also pinned at "
                        + collisions + ". Both appear in the dynamic context. When calling "
                        + "replace_in_file, use the exact full path from the ## section header "
                        + "of the content you are reading — do not mix content across sections.";
            }
        }

        return result;
    }

    /**
     * Called by the AI {@code remove_from_context} tool.
     * {@code relativePath} is relative to the project root.
     * Falls back to filename-only matching if no exact path match is found.
     */
    public String removeEntryForAI(String relativePath) {
        String rel = relativePath.replace("\\", "/");

        // 1. Exact path match
        WatchedEntry entry = entries.stream()
                .filter(e -> e.source == Source.AI && e.relativePath.replace("\\", "/").equals(rel))
                .findFirst().orElse(null);

        // 2. Filename-only fallback (e.g. "Foo.java" matches "src/main/.../Foo.java")
        if (entry == null) {
            String fileName = Path.of(rel).getFileName().toString();
            entry = entries.stream()
                    .filter(e -> e.source == Source.AI
                            && Path.of(e.relativePath).getFileName().toString().equals(fileName))
                    .findFirst().orElse(null);
        }

        if (entry == null) return "Not found in pinned context: " + relativePath
                + ". Use list_context to see exact pinned paths.";
        getPhaseController().notifyEvicted(entry.relativePath);
        removeEntry(entry);
        return "Unpinned: " + entry.relativePath;
    }

    // ── Plan storage ─────────────────────────────────────────────────────────────

    private String currentPlan = null;

    /** Stores the agent's resolution plan. Visible in the system message every turn. */
    public void setCurrentPlan(String plan) { this.currentPlan = plan; notifyListeners(); }

    /** Returns the active plan, or {@code null} if none has been set. */
    public String getCurrentPlan() { return currentPlan; }

    /** Clears the active plan (called on session reset or explicit clear). */
    public void clearCurrentPlan() { this.currentPlan = null; notifyListeners(); }

    // ── Phase state accessors ─────────────────────────────────────────────────────

    public Phase getCurrentPhase() {
        try { return Phase.valueOf(currentPhaseStr); }
        catch (Exception e) { return Phase.ANALYSE; }
    }
    public void setCurrentPhase(Phase phase) { currentPhaseStr = phase.name(); notifyListeners(); }

    public boolean isLastVerifyClean()                { return lastVerifyClean; }
    public void    setLastVerifyClean(boolean v)      { lastVerifyClean = v; }

    public int  getConsecutiveFailedVerifies()         { return consecutiveFailedVerifies; }
    public void setConsecutiveFailedVerifies(int v)    { consecutiveFailedVerifies = v; }

    public int  getLastErrorCount()                    { return lastErrorCount; }
    public void setLastErrorCount(int v)               { lastErrorCount = v; }

    public boolean isHasEditedThisPhase()              { return hasEditedThisPhase; }
    public void    setHasEditedThisPhase(boolean v)    { hasEditedThisPhase = v; }

    public boolean isHasReadFileThisPhase()            { return hasReadFileThisPhase; }
    public void    setHasReadFileThisPhase(boolean v)  { hasReadFileThisPhase = v; }

    public List<String> getEvictedFiles()              { return new ArrayList<>(evictedFiles); }
    public void setEvictedFiles(List<String> list)     { evictedFiles.clear(); evictedFiles.addAll(list); }

    public String getLastVerifyOutput()                { return lastVerifyOutput; }
    public void   setLastVerifyOutput(String v)        { lastVerifyOutput = v != null ? v : ""; }

    // ── Memory fact accessors ─────────────────────────────────────────────────────

    public List<MemoryFact> getMemoryFacts()           { return new ArrayList<>(memoryFacts); }
    public void saveMemoryFacts(List<MemoryFact> facts){ memoryFacts.clear(); memoryFacts.addAll(facts); }

    // ── Scratchpad accessors ──────────────────────────────────────────────────────

    public String getScratchpadNote()            { return scratchpadNote; }
    public void   setScratchpadNote(String note) { scratchpadNote = note; notifyListeners(); }

    // ── PhaseController ───────────────────────────────────────────────────────────

    public PhaseController getPhaseController() {
        if (phaseController == null) {
            synchronized (this) {
                if (phaseController == null) phaseController = new PhaseController(this);
            }
        }
        return phaseController;
    }

    // ── Context management ────────────────────────────────────────────────────────

    /** Removes all AI-pinned entries at once. */
    public String clearAIEntries() {
        List<WatchedEntry> aiEntries = entries.stream()
                .filter(e -> e.source == Source.AI)
                .toList();
        if (aiEntries.isEmpty()) return "No AI-pinned files to clear.";
        aiEntries.forEach(e -> {
            getPhaseController().notifyEvicted(e.relativePath);
            removeEntry(e);
        });
        return "Cleared " + aiEntries.size() + " pinned file(s) from dynamic context.";
    }

    /** Returns the relative paths of all currently AI-pinned files (not folders). */
    public List<String> getAIPinnedPaths() {
        return entries.stream()
                .filter(e -> e.source == Source.AI && !e.isFolder)
                .map(e -> e.relativePath)
                .toList();
    }

    /** Returns the currently pinned AI paths, one per line. */
    public String listAIEntries() {
        List<String> paths = entries.stream()
                .filter(e -> e.source == Source.AI)
                .map(e -> e.relativePath)
                .toList();
        if (paths.isEmpty()) return "No files currently pinned.";
        return "Currently pinned:\n" + String.join("\n", paths);
    }

    /** Converts an absolute path to a project-relative path. */
    public String relativizePath(Project project, String absPath) {
        String basePath = project.getBasePath();
        if (basePath == null) return absPath;
        return relativize(basePath, absPath);
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private boolean isWatched(String absPath) {
        String basePath = project.getBasePath();
        if (basePath == null) return false;
        String norm = absPath.replace("\\", "/");

        for (WatchedEntry entry : entries) {
            String entryAbs = (basePath + "/" + entry.relativePath).replace("\\", "/");
            if (entry.isFolder) {
                if (norm.startsWith(entryAbs + "/")) return true;
            } else {
                if (norm.equals(entryAbs)) return true;
            }
        }
        return false;
    }

    private void regenerateSnapshot(String absPath) {
        ApplicationManager.getApplication().executeOnPooledThread(
                () -> processSourcePath(Path.of(absPath)));
    }

    private void regenerateAll() {
        String basePath = project.getBasePath();
        if (basePath == null) return;
        for (WatchedEntry entry : entries) {
            if (entry.source == Source.AI) continue; // full content read live — no snapshot needed
            Path p = Path.of(basePath, entry.relativePath);
            generateForPath(entry, p);
        }
    }

    /** Recursively generates snapshots for a path, using Java NIO (no read-action needed). */
    private void generateForPath(WatchedEntry entry, Path path) {
        if (Files.isDirectory(path)) {
            try (var stream = Files.walk(path)) {
                stream.filter(Files::isRegularFile)
                      .forEach(this::processSourcePath);
            } catch (Exception ignored) {}
        } else {
            processSourcePath(path);
        }
    }

    private void processSourcePath(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String stripped = StrippedGenerator.generate(content, path.getFileName().toString());
            writeSnapshot(path.toString(), stripped);
        } catch (Exception ignored) {}
    }

    private void writeSnapshot(String sourceAbsPath, String content) {
        String basePath = project.getBasePath();
        if (basePath == null) return;
        String rel = relativize(basePath, sourceAbsPath);
        Path snapshot = Path.of(basePath, SNAPSHOT_DIR, rel);
        try {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, content, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private void deleteSnapshot(WatchedEntry entry) {
        String basePath = project.getBasePath();
        if (basePath == null) return;
        deleteRecursively(Path.of(basePath, SNAPSHOT_DIR, entry.relativePath));
    }

    private void deleteRecursively(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.walk(path)) {
                    stream.sorted(Comparator.reverseOrder())
                          .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {}
    }

    private void collectSnapshots(Path path, String rel, StringBuilder sb) {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                      .forEach(child -> collectSnapshots(child, rel + "/" + child.getFileName(), sb));
            } catch (Exception ignored) {}
        } else if (Files.isRegularFile(path)) {
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                String fileName = path.getFileName().toString();
                sb.append("## ").append(rel).append('\n');
                sb.append("```").append(StrippedGenerator.langTag(fileName)).append('\n');
                sb.append(content).append('\n');
                sb.append("```\n\n");
            } catch (Exception ignored) {}
        }
    }

    /** Appends full file content for an AI-pinned path (file or folder) to {@code sb}. */
    private void appendFullContent(Path abs, String rel, StringBuilder sb) {
        if (Files.isDirectory(abs)) {
            try (var stream = Files.walk(abs)) {
                stream.filter(Files::isRegularFile)
                      .sorted()
                      .forEach(p -> {
                          String fileRel = rel + "/" + abs.relativize(p).toString().replace("\\", "/");
                          appendSingleFile(p, fileRel, sb);
                      });
            } catch (Exception ignored) {}
        } else {
            appendSingleFile(abs, rel, sb);
        }
    }

    private void appendSingleFile(Path path, String rel, StringBuilder sb) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String filename = path.getFileName().toString();

            sb.append("## ").append(rel).append(" [pinned]\n");

            // Warn when another pinned entry shares the same filename so the model
            // knows to use the full path when calling replace_in_file.
            String normRel = rel.replace("\\", "/");
            List<String> collisions = entries.stream()
                    .filter(e -> !e.isFolder && !e.relativePath.replace("\\", "/").equals(normRel))
                    .map(e -> e.relativePath)
                    .filter(p -> Path.of(p).getFileName().toString().equals(filename))
                    .toList();
            if (!collisions.isEmpty()) {
                sb.append("[DISAMBIGUATION: '").append(filename).append("' also exists at ")
                  .append(collisions).append(". Use file_path='").append(rel)
                  .append("' for the content below — not values from the other file's section.]\n");
            }

            sb.append("```").append(StrippedGenerator.langTag(filename)).append('\n');
            sb.append(content).append('\n');
            sb.append("```\n\n");
        } catch (Exception ignored) {}
    }

    private void notifyListeners() {
        ApplicationManager.getApplication().invokeLater(() -> listeners.forEach(Runnable::run));
    }

    private static String relativize(String basePath, String absPath) {
        String base = basePath.replace("\\", "/");
        String abs  = absPath.replace("\\", "/");
        return abs.startsWith(base + "/") ? abs.substring(base.length() + 1) : abs;
    }

    @Override
    public void dispose() {
        busConn.disconnect();
    }
}
