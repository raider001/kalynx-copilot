package copilot.chat;

import com.intellij.openapi.project.Project;
import copilot.context.ContextManager;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.util.List;

/**
 * Displays the agent's active resolution plan, rendered as Markdown.
 * Refreshes whenever the session changes or the plan is updated.
 */
public class AgentPlanPanel extends JPanel {

    private static final Color BG        = new Color(0x1E, 0x1E, 0x1E);
    private static final Color LABEL_FG  = new Color(0x9C, 0xDC, 0xFE);
    private static final Color MUTED     = new Color(0x88, 0x88, 0x88);
    private static final Color BORDER_BG = new Color(0x25, 0x25, 0x25);

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

    private final Project     project;
    private final JEditorPane planPane;

    public AgentPlanPanel(Project project, CopilotChatPanel chatPanel) {
        this.project = project;

        setLayout(new BorderLayout(0, 0));
        setBackground(BG);

        // --- Header ---
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setBackground(BORDER_BG);
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JLabel titleLabel = new JLabel("Agent Plan");
        titleLabel.setForeground(LABEL_FG);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));

        JLabel descLabel = new JLabel("Active resolution plan — updated live as the agent works through each step.");
        descLabel.setForeground(MUTED);
        descLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));

        JButton clearBtn = new JButton("Clear plan");
        clearBtn.addActionListener(e -> ContextManager.getInstance(project).clearCurrentPlan());

        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.add(titleLabel, BorderLayout.WEST);
        titleRow.add(clearBtn, BorderLayout.EAST);

        header.add(titleRow, BorderLayout.NORTH);
        header.add(descLabel, BorderLayout.SOUTH);

        // --- Markdown-rendered plan pane ---
        planPane = new JEditorPane();
        planPane.setEditorKit(new HTMLEditorKit());
        planPane.setEditable(false);
        planPane.setBackground(BG);
        planPane.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        planPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        planPane.setFont(new Font("Segoe UI", Font.PLAIN, 13));

        JScrollPane scroll = new JScrollPane(planPane,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setBackground(BG);
        scroll.getViewport().setBackground(BG);

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        chatPanel.addSessionChangeListener(this::refresh);
        ContextManager.getInstance(project).addListener(this::refresh);

        refresh();
    }

    private void refresh() {
        SwingUtilities.invokeLater(() -> {
            String plan = ContextManager.getInstance(project).getCurrentPlan();
            String body = (plan == null || plan.isBlank())
                    ? "<p style='color:#888888;'><i>No active plan — the agent will create one "
                      + "using create_plan when starting a multi-step task.</i></p>"
                    : MD_RENDERER.render(MD_PARSER.parse(plan));
            planPane.setText(wrapHtml(body));
            planPane.setCaretPosition(0);
        });
    }

    private static String wrapHtml(String body) {
        return "<html><head><style>"
             + "body{font-family:'Segoe UI',sans-serif;font-size:13px;color:#D4D4D4;"
             + "background:#1E1E1E;margin:0;padding:0;}"
             + "h1{color:#9CDCFE;font-size:15px;margin-top:10px;margin-bottom:6px;}"
             + "h2{color:#4EC9B0;font-size:14px;margin-top:6px;margin-bottom:4px;}"
             + "h3{color:#9CDCFE;font-size:13px;margin-top:10px;margin-bottom:4px;}"
             + "p{margin:3px 0 5px 0;}"
             + "hr{border:none;border-top:1px solid #3A3A3A;margin:12px 0 8px 0;}"
             + "ul,ol{margin:4px 0;padding-left:22px;}"
             + "li{margin-bottom:3px;}"
             + "strong{color:#CE9178;}"
             + "em{color:#9CDCFE;}"
             + "code{font-family:'JetBrains Mono',monospace;font-size:12px;"
             + "color:#CE9178;background:#2D2D2D;padding:0 3px;}"
             + "del{color:#888888;}"
             + "input[type=checkbox]{margin-right:4px;}"
             + "</style></head><body>" + body + "</body></html>";
    }
}
