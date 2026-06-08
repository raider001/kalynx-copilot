package copilot.context;

import com.intellij.openapi.project.Project;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Tool-window panel showing the persistent context file set.
 *
 * Files are added by dragging them onto the Chat panel's input area — chips appear
 * there and the entry is registered here automatically.  This panel acts as the
 * persistent view: entries survive IDE restarts and are visible across conversations.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Drag files onto the Chat input to add them to context.  [− Remove] │ ← toolbar
 * ├──────────────────────────────────────────────────────────────────────┤
 * │  📄 src/main/java/copilot/AgentSession.java                          │
 * │  📁 src/main/java/copilot/agent/                                     │ ← list
 * ├──────────────────────────────────────────────────────────────────────┤
 * │  2 entries  •  snapshots auto-update on save                         │ ← status
 * └──────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class ContextPanel extends JPanel {

    private static final Color BG         = new Color(0x1E, 0x1E, 0x1E);
    private static final Color TOOLBAR_BG = new Color(0x2D, 0x2D, 0x2D);
    private static final Color BORDER_COL = new Color(0x3C, 0x3C, 0x3C);
    private static final Color DIM_FG     = new Color(0x80, 0x80, 0x80);
    private static final Color SEL_BG     = new Color(0x26, 0x4F, 0x78);

    private final ContextManager manager;

    private final DefaultListModel<ContextManager.WatchedEntry>  listModel = new DefaultListModel<>();
    private final JList<ContextManager.WatchedEntry>             entryList = new JList<>(listModel);
    private final JLabel                                         statusLabel;
    private final JButton                                        removeBtn;

    public ContextPanel(Project project) {
        this.manager = ContextManager.getInstance(project);

        setLayout(new BorderLayout());
        setBackground(BG);

        // ── Toolbar ────────────────────────────────────────────────────────────────
        JLabel hint = new JLabel("Drag files onto the Chat input to add");
        hint.setForeground(DIM_FG);
        hint.setFont(new Font("Segoe UI", Font.ITALIC, 11));

        removeBtn = new JButton("− Remove");
        removeBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        removeBtn.setFocusPainted(false);
        removeBtn.setEnabled(false);
        removeBtn.addActionListener(e -> removeSelected());

        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setBackground(TOOLBAR_BG);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COL),
                new EmptyBorder(4, 8, 4, 8)));
        toolbar.add(hint, BorderLayout.CENTER);
        toolbar.add(removeBtn, BorderLayout.EAST);
        add(toolbar, BorderLayout.NORTH);

        // ── Entry list ─────────────────────────────────────────────────────────────
        entryList.setCellRenderer(new EntryCellRenderer());
        entryList.setBackground(BG);
        entryList.setSelectionBackground(SEL_BG);
        entryList.setFixedCellHeight(26);
        entryList.setBorder(new EmptyBorder(2, 2, 2, 2));
        entryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) removeBtn.setEnabled(entryList.getSelectedValue() != null);
        });

        JScrollPane scroll = new JScrollPane(entryList,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG);
        add(scroll, BorderLayout.CENTER);

        // ── Status bar ─────────────────────────────────────────────────────────────
        statusLabel = new JLabel(" ");
        statusLabel.setForeground(DIM_FG);
        statusLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COL),
                new EmptyBorder(3, 8, 3, 8)));
        statusLabel.setBackground(TOOLBAR_BG);
        statusLabel.setOpaque(true);
        add(statusLabel, BorderLayout.SOUTH);

        refreshList();
        manager.addListener(this::refreshList);
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private void removeSelected() {
        ContextManager.WatchedEntry sel = entryList.getSelectedValue();
        if (sel != null) manager.removeEntry(sel);
    }

    private void refreshList() {
        SwingUtilities.invokeLater(() -> {
            ContextManager.WatchedEntry prev = entryList.getSelectedValue();
            listModel.clear();
            manager.getEntries().forEach(listModel::addElement);
            if (prev != null) entryList.setSelectedValue(prev, false);

            int n = listModel.size();
            statusLabel.setText(n == 0
                    ? "No context files yet"
                    : n + (n == 1 ? " entry" : " entries") + "  •  snapshots auto-update on save");
        });
    }

    // ── Cell renderer ─────────────────────────────────────────────────────────────

    private static class EntryCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            ContextManager.WatchedEntry entry = (ContextManager.WatchedEntry) value;
            JLabel lbl = (JLabel) super.getListCellRendererComponent(
                    list, entry.relativePath, index, isSelected, cellHasFocus);
            lbl.setIcon(entry.isFolder
                    ? com.intellij.icons.AllIcons.Nodes.Folder
                    : com.intellij.icons.AllIcons.FileTypes.Text);
            lbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            lbl.setBorder(new EmptyBorder(2, 6, 2, 6));
            if (!isSelected) {
                lbl.setBackground(new Color(0x1E, 0x1E, 0x1E));
                lbl.setForeground(new Color(0xD4, 0xD4, 0xD4));
            }
            return lbl;
        }
    }
}
