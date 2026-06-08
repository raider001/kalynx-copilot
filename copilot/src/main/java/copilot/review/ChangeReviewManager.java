package copilot.review;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Project-level service that tracks live file changes awaiting user review. */
@State(name = "KalynxCopilotReview", storages = @Storage("kalynxCopilotReview.xml"))
public class ChangeReviewManager implements PersistentStateComponent<ChangeReviewManager.State> {

    private final Project project;
    private final List<PendingChange> changes = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<PendingChange>>> listeners = new CopyOnWriteArrayList<>();

    public ChangeReviewManager(Project project) {
        this.project = project;
    }

    public static ChangeReviewManager getInstance(Project project) {
        return project.getService(ChangeReviewManager.class);
    }

    // ------------------------------------------------------------------
    // Persistent state
    // ------------------------------------------------------------------

    /** Serialisable record of a single pending change — mutable fields required by XML serialiser. */
    public static class State {
        public List<ChangeRecord> changes = new ArrayList<>();

        public static class ChangeRecord {
            public String relativePath    = "";
            public String absolutePath    = "";
            public String originalContent = "";
            public String newContent      = "";
            public String changeType      = "MODIFY";
        }
    }

    @Override
    public @Nullable State getState() {
        State state = new State();
        for (PendingChange c : changes) {
            State.ChangeRecord rec = new State.ChangeRecord();
            rec.relativePath    = c.relativePath;
            rec.absolutePath    = c.absolutePath;
            rec.originalContent = c.originalContent != null ? c.originalContent : "";
            rec.newContent      = c.newContent      != null ? c.newContent      : "";
            rec.changeType      = c.changeType.name();
            state.changes.add(rec);
        }
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        changes.clear();
        for (State.ChangeRecord rec : state.changes) {
            try {
                changes.add(new PendingChange(
                        rec.relativePath,
                        rec.absolutePath,
                        rec.originalContent.isEmpty() ? null : rec.originalContent,
                        rec.newContent,
                        PendingChange.ChangeType.valueOf(rec.changeType)));
            } catch (Exception ignored) {} // skip malformed / stale records
        }
        // Don't call notifyListeners() — the UI hasn't attached yet at load time.
    }

    // ------------------------------------------------------------------

    /**
     * Registers a new change for review. If a change for the same file already exists
     * (from a previous agent action), the pre-AI original is preserved so the diff
     * always shows the cumulative change from before any AI edits.
     */
    public void addChange(PendingChange change) {
        PendingChange existing = changes.stream()
                .filter(c -> c.absolutePath.equals(change.absolutePath))
                .findFirst().orElse(null);

        if (existing != null) {
            // Keep the baseline from before the first AI edit so the review diff is cumulative.
            // changeType stays as the original (e.g. CREATE stays CREATE even after further edits).
            PendingChange merged = new PendingChange(
                    change.relativePath, change.absolutePath,
                    existing.originalContent,
                    change.newContent,
                    existing.changeType);
            changes.remove(existing);
            changes.add(merged);
        } else {
            changes.add(change);
        }
        notifyListeners();
    }

    public List<PendingChange> getChanges() {
        return Collections.unmodifiableList(changes);
    }

    /** Returns the currently-pending new content for a file path, or null if not pending. */
    public String getStagedContent(String absolutePath) {
        return changes.stream()
                .filter(c -> c.absolutePath.equals(absolutePath))
                .map(c -> c.newContent)
                .findFirst().orElse(null);
    }

    public void addListener(Consumer<List<PendingChange>> listener) {
        listeners.add(listener);
    }

    private void notifyListeners() {
        List<PendingChange> snapshot = List.copyOf(changes);
        ApplicationManager.getApplication().invokeLater(() ->
                listeners.forEach(l -> l.accept(snapshot)));
    }

    // ------------------------------------------------------------------
    // Accept / Reject
    // ------------------------------------------------------------------

    /**
     * Accepts the change — the new content is already on disk, so just remove it from the queue.
     */
    public void acceptAll(PendingChange change) {
        change.getHunks().forEach(h -> h.status = DiffHunk.Status.ACCEPTED);
        changes.remove(change);
        notifyListeners();
    }

    /**
     * Rejects the change — reverts the file to its original content (or deletes it if it was
     * a new-file creation).
     */
    public void rejectAll(PendingChange change) {
        change.getHunks().forEach(h -> h.status = DiffHunk.Status.REJECTED);
        if (change.changeType == PendingChange.ChangeType.CREATE) {
            deleteFromDisk(change.absolutePath);
        } else {
            String original = change.originalContent != null ? change.originalContent : "";
            applyToDisk(change, original);
        }
        changes.remove(change);
        notifyListeners();
    }

    private void applyToDisk(PendingChange change, String content) {
        ApplicationManager.getApplication().invokeAndWait(() ->
                WriteCommandAction.runWriteCommandAction(project, () -> {
                    try {
                        if (change.changeType == PendingChange.ChangeType.CREATE) {
                            File f = new File(change.absolutePath);
                            f.getParentFile().mkdirs();
                            VirtualFile parent = LocalFileSystem.getInstance()
                                    .refreshAndFindFileByPath(f.getParent().replace("\\", "/"));
                            if (parent == null) return;
                            VirtualFile vf = parent.findChild(f.getName());
                            if (vf == null) vf = parent.createChildData(this, f.getName());
                            VfsUtil.saveText(vf, content);
                        } else {
                            VirtualFile vf = LocalFileSystem.getInstance()
                                    .refreshAndFindFileByPath(change.absolutePath.replace("\\", "/"));
                            if (vf == null) return;
                            Document doc = FileDocumentManager.getInstance().getDocument(vf);
                            if (doc == null) return;
                            doc.setText(content);
                            FileDocumentManager.getInstance().saveDocument(doc);
                        }
                    } catch (Exception ignored) {}
                }));
    }

    private void deleteFromDisk(String absolutePath) {
        ApplicationManager.getApplication().invokeAndWait(() ->
                WriteCommandAction.runWriteCommandAction(project, () -> {
                    VirtualFile vf = LocalFileSystem.getInstance()
                            .refreshAndFindFileByPath(absolutePath.replace("\\", "/"));
                    if (vf != null) {
                        try { vf.delete(this); } catch (IOException ignored) {}
                    }
                }));
    }

    public int pendingCount() {
        return changes.size();
    }

    public void clear() {
        changes.clear();
        notifyListeners();
    }
}
