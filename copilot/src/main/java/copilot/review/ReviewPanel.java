package copilot.review;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

/**
 * Tool-window panel for reviewing AI-staged file changes.
 *
 * <p>Layout:
 * <pre>
 * ┌────────────────────────────────────────────────────────────┐
 * │  [Accept All]  [Reject All]           N files pending      │ ← toolbar
 * ├──────────────────┬─────────────────────────────────────────┤
 * │ File list        │ IntelliJ DiffRequestPanel               │
 * │  ✎ Foo.java [✓][✗] │  (real editors — errors, warnings,    │
 * │  + Bar.java [✓][✗] │   inspections, syntax highlighting)   │
 * │                  ├─────────────────────────────────────────┤
 * │                  │ ⚠ Cross-reference panel (on reject)     │
 * └──────────────────┴─────────────────────────────────────────┘
 * </pre>
 */
public class ReviewPanel extends JPanel implements Disposable {

    // --- colours ---
    private static final Color BG           = new Color(0x1E, 0x1E, 0x1E);
    private static final Color PANEL_BG     = new Color(0x14, 0x14, 0x14);
    private static final Color BORDER_COLOR = new Color(0x3C, 0x3C, 0x3C);
    private static final Color FG           = new Color(0xD4, 0xD4, 0xD4);
    private static final Color DIM_FG       = new Color(0x80, 0x80, 0x80);
    private static final Color XREF_BG      = new Color(0x2C, 0x25, 0x0A);
    private static final Color XREF_FG      = new Color(0xFF, 0xCC, 0x55);
    private static final Color LINK_FG      = new Color(0x56, 0xA0, 0xD8);
    private static final Color ACCEPT_FG    = new Color(0x6A, 0xC2, 0x6A);
    private static final Color REJECT_FG    = new Color(0xC2, 0x6A, 0x6A);

    private final Project project;
    private final ChangeReviewManager manager;
    private final Disposable panelDisposable = Disposer.newDisposable("ReviewPanel");

    private final DefaultListModel<PendingChange> fileListModel;
    private final JList<PendingChange> fileList;
    private final JPanel diffWrapper;
    private final JPanel crossRefPanel;
    private final JLabel statusLabel;
    private final JButton acceptFileBtn;
    private final JButton rejectFileBtn;

    private PendingChange selectedChange = null;
    private DiffRequestPanel currentDiffPanel = null;
    private javax.swing.Timer pulseTimer = null;

    /** Tracks which paths we've already seen to detect newly staged CREATE files. */
    private final Set<String> knownChangePaths = new HashSet<>();

    public ReviewPanel(Project project) {
        this.project = project;
        this.manager = ChangeReviewManager.getInstance(project);

        setLayout(new BorderLayout());
        setBackground(PANEL_BG);

        // --- Top toolbar (Accept All / Reject All / status) ---
        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setBackground(BG);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));

        JButton acceptAllBtn = makeButton("Accept All", ACCEPT_FG);
        acceptAllBtn.addActionListener(e -> acceptAllChanges());
        JButton rejectAllBtn = makeButton("Reject All", REJECT_FG);
        rejectAllBtn.addActionListener(e -> rejectAllChanges());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnPanel.setOpaque(false);
        btnPanel.add(acceptAllBtn);
        btnPanel.add(rejectAllBtn);

        statusLabel = new JLabel("No pending changes");
        statusLabel.setForeground(DIM_FG);
        statusLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));

        JPanel rightInfo = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightInfo.setOpaque(false);
        rightInfo.add(statusLabel);

        toolbar.add(btnPanel, BorderLayout.WEST);
        toolbar.add(rightInfo, BorderLayout.EAST);
        add(toolbar, BorderLayout.NORTH);

        // --- File list (left) ---
        // Build the file list first, then wire up buttons that reference it
        acceptFileBtn = makeButton("Accept", ACCEPT_FG);
        acceptFileBtn.setEnabled(false);
        acceptFileBtn.setIcon(AllIcons.Actions.Commit);

        rejectFileBtn = makeButton("Reject", REJECT_FG);
        rejectFileBtn.setEnabled(false);
        rejectFileBtn.setIcon(AllIcons.Actions.Rollback);

        fileListModel = new DefaultListModel<>();
        fileList = new JList<>(fileListModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setCellRenderer(new ChangeListCellRenderer());
        fileList.setBackground(BG);
        fileList.setFixedCellHeight(30);
        fileList.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        fileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                PendingChange sel = fileList.getSelectedValue();
                if (sel != null) selectChange(sel);
                boolean has = sel != null;
                acceptFileBtn.setEnabled(has);
                rejectFileBtn.setEnabled(has);
            }
        });

        // Action listeners added after fileList is fully initialized
        acceptFileBtn.addActionListener(e -> {
            PendingChange sel = fileList.getSelectedValue();
            if (sel != null) manager.acceptAll(sel);
        });
        rejectFileBtn.addActionListener(e -> {
            PendingChange sel = fileList.getSelectedValue();
            if (sel != null) { showCrossRefs(sel); manager.rejectAll(sel); }
        });

        JScrollPane fileScroll = new JScrollPane(fileList,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        fileScroll.setBorder(null);
        fileScroll.getViewport().setBackground(BG);

        JPanel fileActionBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 3));
        fileActionBar.setBackground(new Color(0x1A, 0x1A, 0x1A));
        fileActionBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR));
        fileActionBar.add(acceptFileBtn);
        fileActionBar.add(rejectFileBtn);

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER_COLOR));
        leftPanel.add(fileScroll, BorderLayout.CENTER);
        leftPanel.add(fileActionBar, BorderLayout.SOUTH);

        // --- Diff area (right) ---
        diffWrapper = new JPanel(new BorderLayout());
        diffWrapper.setBackground(BG);

        JLabel placeholder = new JLabel("Select a file to review");
        placeholder.setForeground(DIM_FG);
        placeholder.setHorizontalAlignment(SwingConstants.CENTER);
        placeholder.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        diffWrapper.add(placeholder, BorderLayout.CENTER);

        crossRefPanel = new JPanel();
        crossRefPanel.setLayout(new BoxLayout(crossRefPanel, BoxLayout.Y_AXIS));
        crossRefPanel.setBackground(XREF_BG);
        crossRefPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x66, 0x44, 0x00)),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        crossRefPanel.setVisible(false);

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(diffWrapper, BorderLayout.CENTER);
        rightPanel.add(crossRefPanel, BorderLayout.SOUTH);

        JBSplitter splitter = new JBSplitter(false, 0.25f);
        splitter.setFirstComponent(leftPanel);
        splitter.setSecondComponent(rightPanel);
        add(splitter, BorderLayout.CENTER);

        manager.addListener(this::onChangesUpdated);
        onChangesUpdated(manager.getChanges());
    }

    // ------------------------------------------------------------------
    // Cell renderer — uses AllIcons so glyphs always render correctly
    // ------------------------------------------------------------------

    private static class ChangeListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            PendingChange change = (PendingChange) value;
            JLabel lbl = (JLabel) super.getListCellRendererComponent(
                    list, change.fileName(), index, isSelected, cellHasFocus);
            lbl.setIcon(change.changeType == PendingChange.ChangeType.CREATE
                    ? AllIcons.General.Add : AllIcons.Actions.Edit);
            lbl.setToolTipText(change.relativePath);
            lbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            lbl.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            if (!isSelected) {
                lbl.setBackground(new Color(0x1E, 0x1E, 0x1E));
                lbl.setForeground(new Color(0xD4, 0xD4, 0xD4));
            }
            return lbl;
        }
    }

    // ------------------------------------------------------------------
    // Data update
    // ------------------------------------------------------------------

    private void onChangesUpdated(List<PendingChange> changes) {
        SwingUtilities.invokeLater(() -> {
            // Detect newly staged CREATE files
            PendingChange newCreate = null;
            for (PendingChange c : changes) {
                if (c.changeType == PendingChange.ChangeType.CREATE
                        && !knownChangePaths.contains(c.absolutePath)) {
                    newCreate = c;
                    break;
                }
            }
            knownChangePaths.clear();
            changes.forEach(c -> knownChangePaths.add(c.absolutePath));

            rebuildFileList(changes);
            int n = changes.size();
            statusLabel.setText(n == 0 ? "No pending changes"
                    : n + " file" + (n == 1 ? "" : "s") + " pending");

            if (selectedChange != null && !changes.contains(selectedChange)) {
                selectedChange = null;
                clearDiffView();
            }

            if (newCreate != null) {
                selectChange(newCreate);
            }
            updateTabIndicator(!changes.isEmpty());
        });
    }

    private void rebuildFileList(List<PendingChange> changes) {
        // Suspend the selection listener while we reload the model
        fileListModel.clear();
        changes.forEach(fileListModel::addElement);
        // Restore selection highlight without firing selectChange again
        if (selectedChange != null && changes.contains(selectedChange)) {
            fileList.setSelectedValue(selectedChange, false);
        } else {
            fileList.clearSelection();
        }
    }

    private void selectChange(PendingChange change) {
        selectedChange = change;
        fileList.setSelectedValue(change, true);
        rebuildDiffView(change);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------
    // Diff view
    // ------------------------------------------------------------------
    // Left (original): text + FileType — lexer-based syntax colouring only.
    //   Pre-existing errors in the old code are irrelevant noise; we suppress
    //   them by not backing this side with a PSI file.
    //
    // Right (staged): LightVirtualFile — full PSI analysis.
    //   This is the code being reviewed, so errors, warnings, and unused-symbol
    //   hints should all appear here exactly as they would in a real editor.

    private void rebuildDiffView(PendingChange change) {
        clearDiffView();

        FileType fileType = FileTypeRegistry.getInstance().getFileTypeByFileName(change.fileName());
        DiffContentFactory dcf = DiffContentFactory.getInstance();

        // Left: syntax highlight only — no PSI analysis on the old code
        DiffContent leftContent = (change.originalContent != null && !change.originalContent.isEmpty())
                ? dcf.create(project, change.originalContent, fileType)
                : dcf.createEmpty();

        // Right: full PSI-backed analysis on the staged content
        LightVirtualFile rightVf = new LightVirtualFile(change.fileName(), fileType, change.newContent);
        DiffContent rightContent = dcf.create(project, rightVf);

        String title = change.changeType == PendingChange.ChangeType.CREATE
                ? "New file: " + change.fileName()
                : change.fileName();
        SimpleDiffRequest request = new SimpleDiffRequest(
                title, leftContent, rightContent, "Original", "Applied");

        currentDiffPanel = DiffManager.getInstance().createRequestPanel(project, panelDisposable, null);
        currentDiffPanel.setRequest(request);

        diffWrapper.add(currentDiffPanel.getComponent(), BorderLayout.CENTER);
        diffWrapper.revalidate();
        diffWrapper.repaint();
    }

    private void clearDiffView() {
        if (currentDiffPanel != null) {
            Disposer.dispose(currentDiffPanel);
            currentDiffPanel = null;
        }
        diffWrapper.removeAll();
        crossRefPanel.removeAll();
        crossRefPanel.setVisible(false);
        diffWrapper.revalidate();
        diffWrapper.repaint();
    }

    // ------------------------------------------------------------------
    // Cross-reference display (shown below the diff when rejecting)
    // ------------------------------------------------------------------

    private void showCrossRefs(PendingChange change) {
        // Collect references to any symbols this change was adding
        Map<String, List<String>> refs = new LinkedHashMap<>();
        for (DiffHunk hunk : change.getHunks()) {
            Map<String, List<String>> hunkRefs = CrossRefDetector.findReferences(
                    hunk, change, manager.getChanges(), project);
            hunkRefs.forEach((sym, locations) ->
                    refs.computeIfAbsent(sym, k -> new ArrayList<>()).addAll(locations));
        }
        if (refs.isEmpty()) return;

        crossRefPanel.removeAll();

        JLabel warning = new JLabel("⚠  Rejected symbols may be referenced elsewhere:");
        warning.setForeground(XREF_FG);
        warning.setFont(new Font("Segoe UI", Font.BOLD, 11));
        crossRefPanel.add(warning);
        crossRefPanel.add(Box.createVerticalStrut(4));

        for (Map.Entry<String, List<String>> entry : refs.entrySet()) {
            for (String ref : entry.getValue()) {
                String[] parts = ref.split(":");
                String filePart = parts[0];
                int line = parts.length > 1 ? parseIntSafe(parts[1]) : -1;

                JLabel link = new JLabel("    → " + entry.getKey() + "  in  " + filePart
                        + (line > 0 ? " : " + line : ""));
                link.setForeground(LINK_FG);
                link.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                link.setToolTipText("Open " + filePart);
                link.addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent e) { openFile(filePart); }
                    @Override public void mouseEntered(MouseEvent e) { link.setForeground(Color.WHITE); }
                    @Override public void mouseExited(MouseEvent e)  { link.setForeground(LINK_FG); }
                });
                crossRefPanel.add(link);
            }
        }

        crossRefPanel.setVisible(true);
        crossRefPanel.revalidate();
        crossRefPanel.repaint();
    }

    private void openFile(String relativePath) {
        String base = project.getBasePath();
        if (base == null) return;
        VirtualFile vf = LocalFileSystem.getInstance()
                .refreshAndFindFileByPath((base + "/" + relativePath).replace("\\", "/"));
        if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true);
    }

    // ------------------------------------------------------------------
    // Bulk actions
    // ------------------------------------------------------------------

    private void acceptAllChanges() {
        new ArrayList<>(manager.getChanges()).forEach(manager::acceptAll);
    }

    private void rejectAllChanges() {
        List<PendingChange> snapshot = new ArrayList<>(manager.getChanges());
        // Show cross-refs for the currently selected file, then reject everything
        if (selectedChange != null) showCrossRefs(selectedChange);
        snapshot.forEach(manager::rejectAll);
    }

    // ------------------------------------------------------------------
    // Tab indicator
    // ------------------------------------------------------------------

    // ○ (U+25CB) → ◑ (U+25D1) → ● (U+25CF) → ◑ → ○  — empty / half / full pulse
    private static final String[] PULSE_FRAMES    = { "○", "◑", "●", "◑", "○" };
    private static final int       PULSE_PEAK_IDX  = 2; // index of "●", the widest frame

    private void updateTabIndicator(boolean hasPending) {
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow("Kalynx Copilot");
        if (tw == null) return;
        for (Content c : tw.getContentManager().getContents()) {
            if (c.getDisplayName().startsWith("Review")) {
                if (hasPending) startPulse(c);
                else            stopPulse(c);
                break;
            }
        }
    }

    private void startPulse(Content content) {
        if (pulseTimer != null && pulseTimer.isRunning()) return;

        // Apply the widest frame first so the label is at its maximum size when we measure it.
        content.setDisplayName("Review " + PULSE_FRAMES[PULSE_PEAK_IDX]);

        // Defer one EDT cycle: by then IntelliJ has laid out the label at max width,
        // so we can read the real preferred size and lock it before animation begins.
        SwingUtilities.invokeLater(() -> {
            lockTabLabelWidth(content);
            final int[] frame = {PULSE_PEAK_IDX};
            pulseTimer = new javax.swing.Timer(150, e -> {
                frame[0] = (frame[0] + 1) % PULSE_FRAMES.length;
                content.setDisplayName("Review " + PULSE_FRAMES[frame[0]]);
            });
            pulseTimer.start();
        });
    }

    private void stopPulse(Content content) {
        if (pulseTimer != null) {
            pulseTimer.stop();
            pulseTimer = null;
        }
        content.setDisplayName("Review");
    }

    /**
     * Walks IntelliJ's content-tab component tree to find the JLabel that displays
     * the "Review" tab name, then uses FontMetrics to calculate the exact pixel width
     * of every pulse frame and locks the label's preferred size to the maximum.
     * After this call the tab width is constant regardless of which frame is shown.
     */
    private static void lockTabLabelWidth(Content content) {
        ContentManager cm = content.getManager();
        if (cm == null) return;
        findAndLockTabLabel(cm.getComponent());
    }

    private static void findAndLockTabLabel(Component root) {
        if (root instanceof JLabel lbl) {
            String raw = lbl.getText();
            if (raw != null && stripHtml(raw).startsWith("Review")) {
                FontMetrics fm = lbl.getFontMetrics(lbl.getFont());
                // Measure every frame at this font to find the true maximum pixel width
                int maxTextPx = 0;
                for (String f : PULSE_FRAMES) {
                    maxTextPx = Math.max(maxTextPx, fm.stringWidth("Review " + f));
                }
                // Extra pixels = preferred width − text width (icon gaps, insets, etc.)
                int extra = lbl.getPreferredSize().width - fm.stringWidth(stripHtml(raw));
                Dimension locked = new Dimension(maxTextPx + extra, lbl.getPreferredSize().height);
                lbl.setMinimumSize(locked);
                lbl.setPreferredSize(locked);
                return;
            }
        }
        if (root instanceof Container c) {
            for (Component child : c.getComponents()) {
                findAndLockTabLabel(child);
            }
        }
    }

    private static String stripHtml(String text) {
        return text.startsWith("<html>") ? text.replaceAll("<[^>]+>", "").trim() : text;
    }

    // ------------------------------------------------------------------
    // Disposable
    // ------------------------------------------------------------------

    @Override
    public void dispose() {
        if (pulseTimer != null) pulseTimer.stop();
        if (currentDiffPanel != null) Disposer.dispose(currentDiffPanel);
        Disposer.dispose(panelDisposable);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static JButton makeButton(String text, Color fg) {
        JButton btn = new JButton(text);
        btn.setForeground(fg);
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btn.setFocusPainted(false);
        return btn;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }
}
