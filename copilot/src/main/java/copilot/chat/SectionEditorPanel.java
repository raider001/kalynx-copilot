package copilot.chat;

import copilot.agent.AgentSession;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class SectionEditorPanel extends JPanel {

    private static final Color BG        = new Color(0x1E, 0x1E, 0x1E);
    private static final Color FG        = new Color(0xD4, 0xD4, 0xD4);
    private static final Color HEADER_BG = new Color(0x25, 0x25, 0x25);
    private static final Color LABEL_FG  = new Color(0x9C, 0xDC, 0xFE);
    private static final Color MUTED     = new Color(0x88, 0x88, 0x88);

    private static final Parser       MD_PARSER;
    private static final HtmlRenderer MD_RENDERER;

    static {
        List<org.commonmark.Extension> exts = List.of(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
                AutolinkExtension.create()
        );
        MD_PARSER   = Parser.builder().extensions(exts).build();
        MD_RENDERER = HtmlRenderer.builder().extensions(exts).build();
    }

    private final JTextArea          textArea;
    private final JEditorPane        viewPane;   // null when !useMarkdownToggle
    private final JPanel             cardPanel;  // null when !useMarkdownToggle
    private final Supplier<String>   getter;
    private final Consumer<String>   setter;
    private boolean                  suppressListener = false;

    public SectionEditorPanel(CopilotChatPanel chatPanel,
                              String title,
                              String description,
                              Supplier<String> getter,
                              Consumer<String> setter,
                              boolean useMarkdownToggle) {
        this.getter = getter;
        this.setter = setter;

        setLayout(new BorderLayout(0, 0));
        setBackground(BG);

        // --- Header ---
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setBackground(HEADER_BG);
        header.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(LABEL_FG);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));

        JLabel descLabel = new JLabel(description);
        descLabel.setForeground(MUTED);
        descLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));

        JButton resetBtn = new JButton("Reset");
        resetBtn.setToolTipText("Reset to default");
        resetBtn.addActionListener(e -> reset());

        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.add(titleLabel, BorderLayout.WEST);
        titleRow.add(resetBtn, BorderLayout.EAST);

        header.add(titleRow, BorderLayout.NORTH);
        header.add(descLabel, BorderLayout.SOUTH);

        // --- Text area (always created) ---
        textArea = new JTextArea();
        textArea.setFont(new Font("JetBrains Mono", Font.PLAIN, 12));
        textArea.setBackground(BG);
        textArea.setForeground(FG);
        textArea.setCaretColor(FG);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { onChange(); }
            @Override public void removeUpdate(DocumentEvent e)  { onChange(); }
            @Override public void changedUpdate(DocumentEvent e) { onChange(); }
        });

        JScrollPane editScroll = new JScrollPane(textArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        editScroll.setBorder(null);
        editScroll.getViewport().setBackground(BG);

        if (useMarkdownToggle) {
            // VIEW pane (Markdown rendered)
            viewPane = new JEditorPane();
            viewPane.setEditorKit(new HTMLEditorKit());
            viewPane.setEditable(false);
            viewPane.setBackground(BG);
            viewPane.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            viewPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            viewPane.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            viewPane.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
            viewPane.setToolTipText("Click to edit");

            JScrollPane viewScroll = new JScrollPane(viewPane,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            viewScroll.setBorder(null);
            viewScroll.getViewport().setBackground(BG);

            cardPanel = new JPanel(new CardLayout());
            cardPanel.add(viewScroll, "VIEW");
            cardPanel.add(editScroll, "EDIT");

            viewPane.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    showCard("EDIT");
                    textArea.requestFocusInWindow();
                }
            });

            textArea.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    refreshViewPane();
                    showCard("VIEW");
                }
            });

            add(header, BorderLayout.NORTH);
            add(cardPanel, BorderLayout.CENTER);
        } else {
            viewPane  = null;
            cardPanel = null;
            add(header, BorderLayout.NORTH);
            add(editScroll, BorderLayout.CENTER);
        }

        chatPanel.addSessionChangeListener(this::loadFromSession);
        loadFromSession();
    }

    private void showCard(String name) {
        if (cardPanel != null) ((CardLayout) cardPanel.getLayout()).show(cardPanel, name);
    }

    private void refreshViewPane() {
        if (viewPane == null) return;
        String md = textArea.getText();
        String html = MD_RENDERER.render(MD_PARSER.parse(md.isBlank() ? "*No content*" : md));
        viewPane.setText(wrapHtml(html));
        viewPane.setCaretPosition(0);
    }

    private void onChange() {
        if (!suppressListener) setter.accept(textArea.getText());
    }

    private void loadFromSession() {
        suppressListener = true;
        textArea.setText(getter.get());
        textArea.setCaretPosition(0);
        if (cardPanel != null) {
            refreshViewPane();
            showCard("VIEW");
        }
        suppressListener = false;
    }

    void reset() {}

    void reload() {
        loadFromSession();
    }

    private static String wrapHtml(String body) {
        return "<html><head><style>"
             + "body{font-family:'Segoe UI',sans-serif;font-size:13px;color:#D4D4D4;"
             + "background:#1E1E1E;margin:0;padding:0;}"
             + "h1,h2,h3{color:#9CDCFE;margin-top:12px;margin-bottom:4px;}"
             + "h1{font-size:15px;}h2{font-size:14px;}h3{font-size:13px;}"
             + "p{margin:3px 0 5px 0;}"
             + "ul,ol{margin:4px 0;padding-left:22px;}"
             + "li{margin-bottom:3px;}"
             + "strong{color:#CE9178;}"
             + "em{color:#9CDCFE;}"
             + "code{font-family:'JetBrains Mono',monospace;font-size:12px;"
             + "color:#CE9178;background:#2D2D2D;padding:0 3px;}"
             + "del{color:#888888;}"
             + "hr{border:none;border-top:1px solid #3A3A3A;margin:10px 0;}"
             + "blockquote{margin:6px 0 6px 0;padding:6px 10px;"
             + "border-left:3px solid #CE9178;background:#252525;color:#D4D4D4;}"
             + "</style></head><body>" + body + "</body></html>";
    }

    // Factory methods

    public static SectionEditorPanel dynamicContext(CopilotChatPanel chatPanel) {
        return new SectionEditorPanel(chatPanel,
                "Dynamic Context Management",
                "How the agent reads and manages pinned file content.",
                chatPanel::getDynamicContextSection,
                chatPanel::setDynamicContextSection,
                false) {
            @Override void reset() {
                chatPanel.setDynamicContextSection(AgentSession.defaultDynamicContextSection());
                reload();
            }
        };
    }

    public static SectionEditorPanel agenticWorkflow(CopilotChatPanel chatPanel) {
        return new SectionEditorPanel(chatPanel,
                "Agentic Workflow",
                "The ANALYSE → PLAN → RESOLVE → TEST phases the agent follows.",
                chatPanel::getAgenticWorkflowSection,
                chatPanel::setAgenticWorkflowSection,
                true) {
            @Override void reset() {
                chatPanel.setAgenticWorkflowSection(AgentSession.defaultAgenticWorkflowSection());
                reload();
            }
        };
    }

    public static SectionEditorPanel guidelines(CopilotChatPanel chatPanel) {
        return new SectionEditorPanel(chatPanel,
                "Guidelines",
                "Coding and behavioural rules the agent must follow.",
                chatPanel::getGuidelinesSection,
                chatPanel::setGuidelinesSection,
                true) {
            @Override void reset() {
                chatPanel.setGuidelinesSection(AgentSession.defaultGuidelinesSection());
                reload();
            }
        };
    }
}
