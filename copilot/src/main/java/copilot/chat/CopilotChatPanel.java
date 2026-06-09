package copilot.chat;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import copilot.design.DesignScaffolder;
import copilot.AgentConfig;
import copilot.CopilotAgentsChangedListener;
import copilot.CopilotLoadingStateNotifier;
import copilot.CopilotSettings;
import copilot.CopilotSettingsForm;
import copilot.CopilotUtil;
import copilot.agent.AgentCallback;
import copilot.agent.AgentSession;
import copilot.chat.ChatMessage;
import copilot.context.ContextManager;
import copilot.ui.LoadingButton;

import javax.swing.*;
import javax.swing.text.View;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.GridLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The main Kalynx Copilot chat panel displayed inside the tool window.
 *
 * <p>Layout:
 * <pre>
 * ┌─────────────────────────────────────────┐
 * │  Agent: [combo]               [⚙]       │
 * ├─────────────────────────────────────────┤
 * │  Chat history (HTML scroll pane)        │
 * ├─────────────────────────────────────────┤
 * │  spinner / status label                 │
 * │  Tokens: ─── [===========]              │
 * │  [📄 file.java ×] [📁 src ×]  (chips)  │
 * │  Input text area  (drag files here)     │
 * │  [☑ Include current file]       [Send]  │
 * └─────────────────────────────────────────┘
 * </pre>
 */
public class CopilotChatPanel extends JPanel {

    // --- colours & fonts ---
    private static final Color OUTER_BG        = new Color(0x14, 0x14, 0x14);  // outer container
    private static final Color BG              = new Color(0x1E, 0x1E, 0x1E);  // chat area
    private static final Color USER_BG         = new Color(0x2D, 0x2D, 0x2D);
    private static final Color ASSISTANT_BG    = new Color(0x25, 0x25, 0x25);
    private static final Color CODE_BG         = new Color(0x0D, 0x0D, 0x0D);
    private static final Color USER_FG         = new Color(0xC8, 0xC8, 0xC8);
    private static final Color ASSISTANT_FG    = new Color(0xD4, 0xD4, 0xD4);
    private static final Color ACCENT          = new Color(0x56, 0x9C, 0xD6);
    private static final Color STATUS_FG       = new Color(0x80, 0x80, 0x80);
    private static final Color TOOL_FG         = new Color(0x6A, 0x99, 0x55);
    private static final Color INPUT_BG        = new Color(0x25, 0x25, 0x25);
    private static final Color INPUT_FG        = new Color(0xD4, 0xD4, 0xD4);
    private static final Color BORDER_COLOR    = new Color(0x3C, 0x3C, 0x3C);

    private final Project project;
    private AgentSession agentSession;  // always points to the active session's agent

    /** Whether a request is currently in flight. */
    private volatile boolean requestInProgress = false;

    // Chat sessions
    private final List<ChatSession> sessions = new ArrayList<>();
    private int activeSessionIndex = 0;
    private final JComboBox<String> sessionCombo;
    private boolean updatingSessionCombo = false;

    // Listeners notified whenever the active session changes (used by section editor panels).
    private final List<Runnable> sessionChangeListeners = new ArrayList<>();
    private final List<Runnable> settingsChangeListeners = new ArrayList<>();

    // UI components
    private final JPanel chatMessagesPanel;   // vertical list of bubble panels
    private final JScrollPane chatScroll;
    private final JTextArea inputArea;
    private final JLabel statusLabel;
    private final JLabel tokenLabel;
    private final JProgressBar tokenBar;
    private final LoadingButton sendButton;
    private final JComboBox<String> agentCombo;

    // Token tracking — switches from estimated to real once the API reports actual usage
    private volatile int  knownContextWindow = 0;
    private volatile boolean hasRealTokenData = false;

    // Mode selector
    private ChatMode currentMode = ChatMode.FREE_DEVELOP;
    private JComboBox<String> modeButton;

    // File context chips
    private final JPanel chipsPanel;
    private final List<File> contextFiles = new ArrayList<>();

    /** Suppresses combo change listeners while programmatically updating. */
    private boolean updatingCombo = false;

    // Auto-scroll: follows the bottom unless the user has manually scrolled up.
    private boolean autoScroll         = true;
    private boolean programmaticScroll = false;
    private JPanel  jumpToBottomBar    = null;

    // Streaming state — final response
    private final StringBuilder streamBuffer      = new StringBuilder();
    private JTextArea   streamingContentPane   = null;  // live plain-text pane; replaced with markdown on finalize
    private JPanel      streamingBubble        = null;
    private JPanel      streamingBubbleInner   = null;

    // Streaming state — live reasoning
    private final StringBuilder streamingThinkingBuffer = new StringBuilder();
    private JTextArea   streamingThinkingPane  = null;  // live; stays as plain text after finalize
    private JPanel      streamingThinkingBubble = null;

    // Chunk batching — coalesces rapid background-thread tokens into single EDT flushes
    private final Object        chunkLock                = new Object();
    private final StringBuilder pendingStreamChunks      = new StringBuilder();
    private volatile boolean    streamChunkFlushPending  = false;

    private final Object        thinkingChunkLock           = new Object();
    private final StringBuilder pendingThinkingChunks       = new StringBuilder();
    private volatile boolean    thinkingChunkFlushPending   = false;

    // Thinking animation
    private static final String[] SPINNER = { "|", "/", "-", "\\" };
    private int spinnerFrame = 0;
    private String spinnerBaseText = "Thinking";
    private javax.swing.Timer thinkingTimer;

    public CopilotChatPanel(Project project) {
        this.project = project;

        setLayout(new BorderLayout());
        setBackground(OUTER_BG);

        // --- Agent selector (lives in the button row below the input) ---
        agentCombo = new JComboBox<>();
        agentCombo.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        agentCombo.setToolTipText("Select the AI agent to chat with");
        refreshAgentCombo();
        agentCombo.addActionListener(e -> {
            if (updatingCombo) return;
            int idx = agentCombo.getSelectedIndex();
            if (idx < 0) return;
            CopilotSettings settings = CopilotSettings.getInstance();
            if (idx == settings.activeAgentIndex) return;
            settings.setActiveAgentIndex(idx);
            ChatSession cur = sessions.get(activeSessionIndex);
            cur.agentSession = new AgentSession(project,
                    cur.dynamicContextSection, cur.agenticWorkflowSection, cur.guidelinesSection);
            agentSession = cur.agentSession;
            AgentConfig active = settings.getActiveAgent();
            appendSystemMessage("🔄 Switched to agent: " + active.name
                    + " (" + active.model + ")");
            refetchContextWindow();
        });

        // Subscribe to settings changes so the dropdown refreshes instantly.
        ApplicationManager.getApplication().getMessageBus()
                .connect(project)
                .subscribe(CopilotAgentsChangedListener.TOPIC, () ->
                        SwingUtilities.invokeLater(this::onSettingsChanged));

        // --- Session toolbar (top) ---
        sessionCombo = new JComboBox<>();
        sessionCombo.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        sessionCombo.setToolTipText("Switch chat session");
        sessionCombo.addActionListener(e -> {
            if (updatingSessionCombo) return;
            int idx = sessionCombo.getSelectedIndex();
            if (idx >= 0 && idx != activeSessionIndex) switchToSession(idx);
        });

        JButton newSessionBtn = new JButton("+");
        newSessionBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        newSessionBtn.setForeground(STATUS_FG);
        newSessionBtn.setToolTipText("New chat session");
        newSessionBtn.setBorderPainted(false);
        newSessionBtn.setContentAreaFilled(false);
        newSessionBtn.setFocusPainted(false);
        newSessionBtn.setMargin(new Insets(0, 3, 0, 3));
        newSessionBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        newSessionBtn.addActionListener(e -> createNewSession());

        JButton delSessionBtn = new JButton("−");
        delSessionBtn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        delSessionBtn.setForeground(STATUS_FG);
        delSessionBtn.setToolTipText("Delete current session");
        delSessionBtn.setBorderPainted(false);
        delSessionBtn.setContentAreaFilled(false);
        delSessionBtn.setFocusPainted(false);
        delSessionBtn.setMargin(new Insets(0, 3, 0, 3));
        delSessionBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        delSessionBtn.addActionListener(e -> deleteCurrentSession());

        // BoxLayout keeps the two buttons flush against each other and the combo
        JPanel sessionBtns = new JPanel();
        sessionBtns.setLayout(new BoxLayout(sessionBtns, BoxLayout.X_AXIS));
        sessionBtns.setOpaque(false);
        sessionBtns.add(newSessionBtn);
        sessionBtns.add(delSessionBtn);

        JPanel sessionPanel = new JPanel(new BorderLayout(2, 0));
        sessionPanel.setOpaque(false);
        sessionPanel.add(sessionCombo, BorderLayout.CENTER);
        sessionPanel.add(sessionBtns, BorderLayout.EAST);

        JButton settingsBtn = new JButton(AllIcons.General.Settings);
        settingsBtn.setToolTipText("Kalynx Copilot Settings");
        settingsBtn.setBorderPainted(false);
        settingsBtn.setContentAreaFilled(false);
        settingsBtn.setFocusPainted(false);
        settingsBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        settingsBtn.addActionListener(e -> new CopilotSettingsForm().showAndGet());

        JButton debugContextBtn = new JButton(AllIcons.General.InspectionsEye);
        debugContextBtn.setToolTipText("View current system context sent to the AI");
        debugContextBtn.setBorderPainted(false);
        debugContextBtn.setContentAreaFilled(false);
        debugContextBtn.setFocusPainted(false);
        debugContextBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        debugContextBtn.addActionListener(e -> showSystemContextDialog());

        JPanel toolbarButtons = new JPanel();
        toolbarButtons.setLayout(new BoxLayout(toolbarButtons, BoxLayout.X_AXIS));
        toolbarButtons.setOpaque(false);
        toolbarButtons.add(debugContextBtn);
        toolbarButtons.add(settingsBtn);

        JPanel toolbar = new JPanel(new BorderLayout(4, 0));
        toolbar.setBackground(BG);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)
        ));
        toolbar.add(sessionPanel, BorderLayout.CENTER);
        toolbar.add(toolbarButtons, BorderLayout.EAST);
        add(toolbar, BorderLayout.NORTH);

        // --- Chat area: vertical stack of message bubbles ---
        // ScrollablePanel ensures the JScrollPane viewport always sets this panel's
        // width to match the viewport — guaranteeing correct word-wrap on resize.
        chatMessagesPanel = new ScrollablePanel();
        chatMessagesPanel.setLayout(new BoxLayout(chatMessagesPanel, BoxLayout.Y_AXIS));
        chatMessagesPanel.setBackground(BG);
        chatMessagesPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Restore persisted sessions, or create the default first session
        List<CopilotSettings.SavedSession> saved = CopilotSettings.getInstance().chatSessions;
        if (saved == null || saved.isEmpty()) {
            ChatSession first = new ChatSession("Session 1");
            sessions.add(first);
            updatingSessionCombo = true;
            sessionCombo.addItem(first.name);
            updatingSessionCombo = false;
            agentSession = first.agentSession;
            addBubble(makePlainBubble(
                    "👋 Hello! I'm Kalynx Copilot. Ask me anything about your code.\n"
                            + "Tip: Use Ctrl+Enter to send. Drag files onto the input to add context.",
                    STATUS_FG, BG, 11, false));
        } else {
            for (CopilotSettings.SavedSession ss : saved) {
                ChatSession session = new ChatSession(ss.name);
                session.titled = ss.titled;
                session.plan = ss.plan;
                session.messageHistory.addAll(ss.messages);
                session.agentSession.reloadHistory(ss.messages);
                loadSessionComponents(session, ss.messages);
                sessions.add(session);
                updatingSessionCombo = true;
                sessionCombo.addItem(ss.name);
                updatingSessionCombo = false;
            }
            activeSessionIndex = 0;
            agentSession = sessions.get(0).agentSession;
            // Restore the first session's plan into ContextManager on startup
            ContextManager.getInstance(project).setCurrentPlan(sessions.get(0).plan);
            for (Component c : sessions.get(0).components) chatMessagesPanel.add(c);
            chatMessagesPanel.revalidate();
        }

        JScrollPane chatScroll = new JScrollPane(chatMessagesPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chatScroll.setBorder(null);
        chatScroll.setOpaque(false);
        chatScroll.getViewport().setBackground(BG);
        this.chatScroll = chatScroll;

        // Re-enable auto-scroll when the user drags back to the bottom;
        // disable it when they scroll away. The programmaticScroll guard
        // prevents our own setValue() calls from toggling the flag.
        // prevScrollMax tracks the last known maximum so we can ignore
        // adjustment events caused by content growth (max changes without a user scroll).
        int[] prevScrollMax = {0};
        chatScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (programmaticScroll) return;
            JScrollBar sb = chatScroll.getVerticalScrollBar();
            int max = sb.getMaximum();
            if (max != prevScrollMax[0]) {
                prevScrollMax[0] = max;
                return; // content grew — not a user scroll, don't touch autoScroll
            }
            boolean atBottom = sb.getValue() + sb.getVisibleAmount() >= max - 20;
            autoScroll = atBottom;
            if (jumpToBottomBar != null) jumpToBottomBar.setVisible(!atBottom);
        });

        // Jump-to-bottom bar — shown when the user scrolls up during a live response
        jumpToBottomBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 3));
        jumpToBottomBar.setBackground(BG);
        jumpToBottomBar.setVisible(false);
        JLabel jumpBtn = new JLabel("↓  Jump to latest");
        jumpBtn.setForeground(ACCENT);
        jumpBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        jumpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        jumpBtn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                autoScroll = true;
                jumpToBottomBar.setVisible(false);
                scrollToBottom();
            }
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { jumpBtn.setForeground(ACCENT.brighter()); }
            @Override public void mouseExited(java.awt.event.MouseEvent e)  { jumpBtn.setForeground(ACCENT); }
        });
        jumpToBottomBar.add(jumpBtn);

        JPanel chatWrapper = roundedWrap(chatScroll, BG, BORDER_COLOR, 12);
        chatWrapper.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));

        JPanel chatAreaPanel = new JPanel(new BorderLayout());
        chatAreaPanel.setOpaque(false);
        chatAreaPanel.add(chatWrapper, BorderLayout.CENTER);
        chatAreaPanel.add(jumpToBottomBar, BorderLayout.SOUTH);

        // --- Bottom panel ---
        JPanel bottomPanel = new JPanel(new BorderLayout(0, 4));
        bottomPanel.setBackground(OUTER_BG);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));

        // Status label + token usage bar stacked in NORTH
        JPanel topInfo = new JPanel();
        topInfo.setLayout(new BoxLayout(topInfo, BoxLayout.Y_AXIS));
        topInfo.setBackground(OUTER_BG);

        statusLabel = new JLabel(" ");
        statusLabel.setForeground(STATUS_FG);
        statusLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topInfo.add(statusLabel);

        JPanel tokenPanel = new JPanel(new BorderLayout(6, 0));
        tokenPanel.setBackground(OUTER_BG);
        tokenPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        tokenLabel = new JLabel("Tokens: —");
        tokenLabel.setForeground(STATUS_FG);
        tokenLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        tokenBar = new JProgressBar(0, 100);
        tokenBar.setValue(0);
        tokenBar.setStringPainted(false);
        tokenBar.setPreferredSize(new Dimension(0, 6));
        tokenBar.setBorderPainted(false);
        tokenPanel.add(tokenLabel, BorderLayout.WEST);
        tokenPanel.add(tokenBar, BorderLayout.CENTER);
        topInfo.add(tokenPanel);

        bottomPanel.add(topInfo, BorderLayout.NORTH);

        // Input area
        inputArea = new JTextArea(4, 30);
        inputArea.setBackground(INPUT_BG);
        inputArea.setForeground(INPUT_FG);
        inputArea.setCaretColor(INPUT_FG);
        inputArea.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setOpaque(true);
        inputArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        // Ctrl+Enter to send, Enter = newline
        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && e.isControlDown()) {
                    e.consume();
                    sendMessage();
                }
            }
        });

        JScrollPane inputScroll = new JScrollPane(inputArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        inputScroll.setBorder(null);
        inputScroll.setOpaque(false);
        inputScroll.getViewport().setOpaque(false);
        inputScroll.getViewport().setBackground(INPUT_BG);

        JPanel inputWrapper = roundedWrap(inputScroll, INPUT_BG, BORDER_COLOR, 10);

        // File context chips — wraps onto new lines as more files are added, no scrollbar
        chipsPanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
        chipsPanel.setBackground(OUTER_BG);
        chipsPanel.setVisible(false);

        JPanel centerWrapper = new JPanel(new BorderLayout(0, 4));
        centerWrapper.setBackground(OUTER_BG);
        centerWrapper.add(chipsPanel, BorderLayout.NORTH);
        centerWrapper.add(inputWrapper, BorderLayout.CENTER);
        bottomPanel.add(centerWrapper, BorderLayout.CENTER);

        // Drag-and-drop: installed directly on inputArea (the JTextArea consumes all drag events
        // before they reach any parent panel, so the handler must live here).
        // File drops → add context chips; text drops → insert into the text area (default behaviour).
        TransferHandler fileDropHandler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        || support.isDataFlavorSupported(DataFlavor.stringFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    try {
                        List<File> files = (List<File>) support.getTransferable()
                                .getTransferData(DataFlavor.javaFileListFlavor);
                        SwingUtilities.invokeLater(() -> files.forEach(CopilotChatPanel.this::addContextFile));
                        return true;
                    } catch (Exception ex) {
                        return false;
                    }
                }
                if (support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    try {
                        String s = (String) support.getTransferable()
                                .getTransferData(DataFlavor.stringFlavor);
                        inputArea.replaceSelection(s);
                        return true;
                    } catch (Exception ex) {
                        return false;
                    }
                }
                return false;
            }
        };
        inputArea.setTransferHandler(fileDropHandler);
        chipsPanel.setTransferHandler(fileDropHandler);

        // Button row: [mode ▾] [agent combo] [Send]
        JPanel buttonRow = new JPanel(new BorderLayout(4, 0));
        buttonRow.setBackground(OUTER_BG);

        modeButton = buildModeButton();

        sendButton = new LoadingButton("Send");
        sendButton.setToolTipText("Send message (Ctrl+Enter) — click again to stop");
        sendButton.addActionListener(e -> {
            if (requestInProgress) {
                stopRequest();
            } else {
                sendMessage();
            }
        });

        JPanel leftRow = new JPanel(new BorderLayout(4, 0));
        leftRow.setBackground(OUTER_BG);
        leftRow.add(modeButton, BorderLayout.WEST);
        leftRow.add(agentCombo, BorderLayout.CENTER);

        buttonRow.add(leftRow, BorderLayout.CENTER);
        buttonRow.add(sendButton, BorderLayout.EAST);

        bottomPanel.add(buttonRow, BorderLayout.SOUTH);

        // --- Vertical split: chat (top) / input (bottom) — IntelliJ native splitter ---
        com.intellij.ui.OnePixelSplitter splitPane =
                new com.intellij.ui.OnePixelSplitter(true, 0.75f);
        splitPane.setFirstComponent(chatAreaPanel);
        splitPane.setSecondComponent(bottomPanel);
        splitPane.setHonorComponentsMinimumSize(true);
        add(splitPane, BorderLayout.CENTER);

        // Sync chips with ContextManager — covers entries added via the Context tab
        ContextManager cm = ContextManager.getInstance(project);
        cm.addListener(this::refreshChips);
        // Re-estimate token usage whenever context changes (file pinned/unpinned),
        // but only while we haven't received real usage data from the API yet.
        cm.addListener(() -> SwingUtilities.invokeLater(this::refreshEstimatedTokens));
        refreshChips();

        // Fetch the model's context window on a background thread, then seed the token
        // bar with an estimate of current usage (system prompt + context / 4 chars per token).
        refetchContextWindow();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Pre-populate the input field (e.g. from a right-click action). */
    public void setInputText(String text) {
        inputArea.setText(text);
        inputArea.requestFocusInWindow();
    }

    /**
     * Called by {@link copilot.actions.OpenCopilotSettingsAction} after the settings
     * dialog closes so the agent dropdown stays in sync.
     */
    public void onSettingsChanged() {
        refreshAgentCombo();
        ChatSession cur = sessions.get(activeSessionIndex);
        // Update system-prompt sections in place so conversation history (including any
        // retained tool messages) is preserved across settings changes.
        cur.agentSession.reinitSections(
                cur.dynamicContextSection, cur.agenticWorkflowSection, cur.guidelinesSection);
        settingsChangeListeners.forEach(Runnable::run);
    }

    // ------------------------------------------------------------------
    // Public API for section editor panels
    // ------------------------------------------------------------------

    /** Registers a callback fired whenever the active session changes. */
    public void addSessionChangeListener(Runnable listener) {
        sessionChangeListeners.add(listener);
    }

    public void addSettingsChangeListener(Runnable listener) {
        settingsChangeListeners.add(listener);
    }

    public String getDynamicContextSection() {
        return sessions.get(activeSessionIndex).dynamicContextSection;
    }
    public void setDynamicContextSection(String text) {
        ChatSession s = sessions.get(activeSessionIndex);
        s.dynamicContextSection = text;
        s.agentSession.setDynamicContextSection(text);
    }

    public String getAgenticWorkflowSection() {
        return sessions.get(activeSessionIndex).agenticWorkflowSection;
    }
    public void setAgenticWorkflowSection(String text) {
        ChatSession s = sessions.get(activeSessionIndex);
        s.agenticWorkflowSection = text;
        s.agentSession.setAgenticWorkflowSection(text);
    }

    public String getGuidelinesSection() {
        return sessions.get(activeSessionIndex).guidelinesSection;
    }
    public void setGuidelinesSection(String text) {
        ChatSession s = sessions.get(activeSessionIndex);
        s.guidelinesSection = text;
        s.agentSession.setGuidelinesSection(text);
    }

    public String getCurrentPlan() {
        return ContextManager.getInstance(project).getCurrentPlan();
    }

    // ------------------------------------------------------------------
    // Chat session management
    // ------------------------------------------------------------------

    private void switchToSession(int index) {
        // Save the departing session's plan before leaving it
        if (activeSessionIndex >= 0 && activeSessionIndex < sessions.size() && activeSessionIndex != index) {
            sessions.get(activeSessionIndex).plan = ContextManager.getInstance(project).getCurrentPlan();
        }
        activeSessionIndex = index;
        ChatSession session = sessions.get(index);
        // Restore the incoming session's plan
        ContextManager.getInstance(project).setCurrentPlan(session.plan);
        agentSession = session.agentSession;
        agentSession.setModePrompt(currentMode.buildSystemPrompt());
        hasRealTokenData = false;
        refreshEstimatedTokens();
        chatMessagesPanel.removeAll();
        for (Component c : session.components) chatMessagesPanel.add(c);
        chatMessagesPanel.revalidate();
        chatMessagesPanel.repaint();
        scrollToBottom();
        sessionChangeListeners.forEach(Runnable::run);
    }

    private void createNewSession() {
        ContextManager cm = ContextManager.getInstance(project);
        cm.clearAIEntries();
        // Stash the current session's plan before leaving, then clear for the new session
        sessions.get(activeSessionIndex).plan = cm.getCurrentPlan();
        cm.clearCurrentPlan();
        ChatSession session = new ChatSession("Session " + (sessions.size() + 1));
        sessions.add(session);
        updatingSessionCombo = true;
        sessionCombo.addItem(session.name);
        sessionCombo.setSelectedIndex(sessions.size() - 1);
        updatingSessionCombo = false;
        activeSessionIndex = sessions.size() - 1;
        agentSession = session.agentSession;
        agentSession.setModePrompt(currentMode.buildSystemPrompt());
        hasRealTokenData = false;
        refreshEstimatedTokens();
        chatMessagesPanel.removeAll();
        chatMessagesPanel.revalidate();
        chatMessagesPanel.repaint();
        addBubble(makePlainBubble("New session started.", STATUS_FG, BG, 11, true));
        persistSessions();
    }

    private void deleteCurrentSession() {
        if (sessions.size() <= 1) {
            ContextManager cm = ContextManager.getInstance(project);
            cm.clearAIEntries();
            cm.clearCurrentPlan();
            ChatSession s = sessions.get(0);
            s.components.clear();
            s.messageHistory.clear();
            s.firstUserMessage = null;
            s.titled = false;
            s.plan = null;
            s.name = "Session 1";
            s.agentSession = new AgentSession(project);
            agentSession = s.agentSession;
            updatingSessionCombo = true;
            sessionCombo.removeItemAt(0);
            sessionCombo.insertItemAt(s.name, 0);
            sessionCombo.setSelectedIndex(0);
            updatingSessionCombo = false;
            chatMessagesPanel.removeAll();
            chatMessagesPanel.revalidate();
            chatMessagesPanel.repaint();
            addBubble(makePlainBubble("Session cleared.", STATUS_FG, BG, 11, true));
            persistSessions();
            return;
        }
        int idx = activeSessionIndex;
        sessions.remove(idx);
        updatingSessionCombo = true;
        sessionCombo.removeItemAt(idx);
        int newIdx = Math.min(idx, sessions.size() - 1);
        sessionCombo.setSelectedIndex(newIdx);
        updatingSessionCombo = false;
        // Pre-set so switchToSession doesn't mistakenly save the plan to the wrong session
        activeSessionIndex = newIdx;
        switchToSession(newIdx);
        persistSessions();
    }

    private void persistSessions() {
        // Snapshot the active session's current plan before serializing
        if (activeSessionIndex >= 0 && activeSessionIndex < sessions.size()) {
            sessions.get(activeSessionIndex).plan = ContextManager.getInstance(project).getCurrentPlan();
        }
        List<CopilotSettings.SavedSession> saved = new ArrayList<>();
        for (ChatSession s : sessions) {
            CopilotSettings.SavedSession ss = new CopilotSettings.SavedSession();
            ss.name = s.name;
            ss.titled = s.titled;
            ss.messages.addAll(s.messageHistory);
            ss.plan = s.plan;
            saved.add(ss);
        }
        CopilotSettings.getInstance().chatSessions = saved;
    }

    /** Fires an out-of-band LLM request to generate a short title for the session. */
    private void generateSessionTitle(int sessionIndex, String firstUserMessage) {
        new Thread(() -> {
            try {
                List<ChatMessage> msgs = new ArrayList<>();
                msgs.add(ChatMessage.system(
                        "You generate ultra-short chat titles. Reply with ONLY a 2-5 word title. " +
                        "No quotes, no punctuation, no explanation."));
                String prompt = firstUserMessage.length() > 300
                        ? firstUserMessage.substring(0, 300) : firstUserMessage;
                msgs.add(ChatMessage.user("Title for a chat that starts: " + prompt));

                StringBuilder buf = new StringBuilder();
                CopilotUtil.streamChatRequest(msgs, Collections.emptyList(),
                        4096, 0, buf::append, ignored -> {}, () -> false);

                String title = buf.toString().trim()
                        .replaceAll("^[\"']|[\"']$", "")  // strip surrounding quotes if any
                        .trim();
                if (title.isEmpty() || sessionIndex >= sessions.size()) return;

                SwingUtilities.invokeLater(() -> {
                    ChatSession session = sessions.get(sessionIndex);
                    session.name = title;
                    updatingSessionCombo = true;
                    sessionCombo.removeItemAt(sessionIndex);
                    sessionCombo.insertItemAt(title, sessionIndex);
                    sessionCombo.setSelectedIndex(activeSessionIndex);
                    updatingSessionCombo = false;
                    persistSessions();
                });
            } catch (Exception ignored) {}
        }, "copilot-title").start();
    }

    /** Rebuilds a session's UI bubble list from saved messages (used on startup load). */
    private static void loadSessionComponents(ChatSession session,
                                              List<CopilotSettings.SavedMessage> messages) {
        for (CopilotSettings.SavedMessage msg : messages) {
            JPanel bubble = switch (msg.role) {
                case "user"      -> makeUserBubble(msg.content);
                case "assistant" -> makeAssistantBubble(msg.content);
                default          -> makePlainBubble(msg.content, STATUS_FG, BG, 11, true);
            };
            bubble.setAlignmentX(Component.LEFT_ALIGNMENT);
            session.components.add(bubble);
            session.components.add(Box.createVerticalStrut(4));
        }
    }

    // ------------------------------------------------------------------
    // Agent combo helpers
    // ------------------------------------------------------------------

    private void refreshAgentCombo() {
        updatingCombo = true;
        agentCombo.removeAllItems();
        CopilotSettings settings = CopilotSettings.getInstance();
        for (AgentConfig a : settings.agents) {
            agentCombo.addItem(a.name);
        }
        int active = settings.activeAgentIndex;
        if (active >= 0 && active < agentCombo.getItemCount()) {
            agentCombo.setSelectedIndex(active);
        }
        updatingCombo = false;
    }

    // ------------------------------------------------------------------
    // Private logic
    // ------------------------------------------------------------------

    private void sendMessage() {
        String text = inputArea.getText().trim();
        if (text.isEmpty()) return;
        copilot.CopilotSettings cfg = copilot.CopilotSettings.getInstance();
        if (!cfg.isValidConfig()) {
            String detail = diagnoseMissingConfig(cfg);
            appendSystemMessage("⚠️ " + detail + "  →  Tools → Kalynx Copilot Settings");
            return;
        }

        inputArea.setText("");
        requestInProgress = true;
        sendButton.setText("Stop");
        sendButton.setToolTipText("Cancel the current request");
        appendUserMessage(text);
        startThinkingAnimation("");
        publishLoading(true);

        // Track in session history for persistence and title generation
        ChatSession activeSession = sessions.get(activeSessionIndex);
        if (activeSession.firstUserMessage == null) activeSession.firstUserMessage = text;
        activeSession.messageHistory.add(new CopilotSettings.SavedMessage("user", text));

        List<File> filesToSend = new ArrayList<>(contextFiles);

        new Thread(() -> {
            // Prepend any dragged-in file/folder contents (read on background thread)
            String fullMessage = text;
            if (!filesToSend.isEmpty()) {
                StringBuilder ctx = new StringBuilder();
                for (File f : filesToSend) ctx.append(buildFileContext(f));
                fullMessage = ctx + "\n\n" + text;
            }
            final String messageToSend = fullMessage;
            try {
                AgentCallback callback = new AgentCallback() {
                    @Override
                    public void onStatus(String message) {
                        SwingUtilities.invokeLater(() ->
                                setSpinnerBase(message));
                    }
                    @Override
                    public void onThinking(String thought) {
                        SwingUtilities.invokeLater(() -> appendThinkingBlock(thought));
                    }
                    @Override
                    public void onThinkingChunk(String chunk) {
                        synchronized (thinkingChunkLock) {
                            pendingThinkingChunks.append(chunk);
                            if (!thinkingChunkFlushPending) {
                                thinkingChunkFlushPending = true;
                                SwingUtilities.invokeLater(() -> {
                                    String batch;
                                    synchronized (thinkingChunkLock) {
                                        batch = pendingThinkingChunks.toString();
                                        pendingThinkingChunks.setLength(0);
                                        thinkingChunkFlushPending = false;
                                    }
                                    appendThinkingChunk(batch);
                                });
                            }
                        }
                    }
                    @Override
                    public void onThinkingStreamEnd() {
                        SwingUtilities.invokeLater(() -> finalizeThinkingStream());
                    }
                    @Override
                    public void onToolStart(String toolName, String description) {
                        SwingUtilities.invokeLater(() -> {
                            setSpinnerBase(description);
                            appendToolMessage("🔧 " + description + "…");
                        });
                    }
                    @Override
                    public void onToolEnd(String toolName, boolean success) {
                        SwingUtilities.invokeLater(() ->
                                setSpinnerBase(success ? "✅ Done" : "❌ Failed"));
                    }
                    @Override
                    public void onToolError(String toolName, String errorMessage) {
                        SwingUtilities.invokeLater(() -> appendToolErrorMessage(toolName, errorMessage));
                    }
                    @Override
                    public void onToolResult(String toolName, String result) {
                        SwingUtilities.invokeLater(() -> appendToolResultMessage(toolName, result));
                    }
                    @Override
                    public void onEditResult(String filePath, String oldCode, String newCode) {
                        SwingUtilities.invokeLater(() -> appendEditResultMessage(filePath, oldCode, newCode));
                    }
                    @Override
                    public void onUsage(int promptTokens, int completionTokens, int contextLength) {
                        hasRealTokenData = true;
                        SwingUtilities.invokeLater(() -> updateTokenBar(promptTokens, completionTokens, contextLength));
                    }
                    @Override
                    public void onResponseChunk(String chunk) {
                        synchronized (chunkLock) {
                            pendingStreamChunks.append(chunk);
                            if (!streamChunkFlushPending) {
                                streamChunkFlushPending = true;
                                SwingUtilities.invokeLater(() -> {
                                    String batch;
                                    synchronized (chunkLock) {
                                        batch = pendingStreamChunks.toString();
                                        pendingStreamChunks.setLength(0);
                                        streamChunkFlushPending = false;
                                    }
                                    appendStreamChunk(batch);
                                });
                            }
                        }
                    }
                    @Override
                    public void onResponseAborted() {
                        SwingUtilities.invokeLater(() -> abortStream());
                    }
                    @Override
                    public void onCompressionStarted() {
                        SwingUtilities.invokeLater(() ->
                                appendSystemMessage("🗜 Compressing conversation history…"));
                    }
                    @Override
                    public void onCompressionDone(int originalTokens, int compressedTokens,
                                                  String snapshotPath) {
                        SwingUtilities.invokeLater(() ->
                                appendCompressionNotice(originalTokens, compressedTokens, snapshotPath));
                    }
                };

                String response = agentSession.chat(messageToSend, callback);
                SwingUtilities.invokeLater(() -> {
                    finishRequest();
                    finalizeStream(response);
                    scrollToBottom();
                });
            } catch (Throwable ex) {
                SwingUtilities.invokeLater(() -> {
                    finishRequest();
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    appendSystemMessage("❌ Error: " + msg);
                });
            }
        }, "copilot-agent").start();
    }

    /** Cancels the in-flight request. */
    private void stopRequest() {
        agentSession.cancel();
        // UI will be reset when the thread returns via finishRequest()
    }

    /** Called on the EDT when a request finishes (normally, cancelled, or errored). */
    private void finishRequest() {
        requestInProgress = false;
        publishLoading(false);
        stopThinkingAnimation();
        sendButton.setText("Send");
        sendButton.setToolTipText("Send message (Ctrl+Enter) — click again to stop");
        if (jumpToBottomBar != null) jumpToBottomBar.setVisible(false);
        // Drain any pending chunk batches so no stale tokens bleed into the next request
        synchronized (chunkLock) {
            pendingStreamChunks.setLength(0);
            streamChunkFlushPending = false;
        }
        synchronized (thinkingChunkLock) {
            pendingThinkingChunks.setLength(0);
            thinkingChunkFlushPending = false;
        }
        persistSessions();
    }

    /**
     * Re-fetches the context window for the currently active agent's model on a
     * background thread, then refreshes the token bar. Called on startup and whenever
     * the user switches agents so the max always reflects the new model.
     */
    private void refetchContextWindow() {
        hasRealTokenData = false;
        knownContextWindow = 0;
        SwingUtilities.invokeLater(() -> updateTokenBar(0, 0, 0)); // show ??? immediately
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CopilotSettings s = CopilotSettings.getInstance();
            int contextWindow = CopilotUtil.fetchContextWindow(
                    s.getApiEndpoint(), s.getApiKey(), s.getModel());
            knownContextWindow = contextWindow;
            SwingUtilities.invokeLater(this::refreshEstimatedTokens);
        });
    }

    /**
     * Estimates current token usage from system prompt + dynamic context using the
     * ~4 chars-per-token heuristic. Called at startup and whenever context changes,
     * but only while we don't yet have real usage data from the API.
     */
    private void refreshEstimatedTokens() {
        if (hasRealTokenData || knownContextWindow <= 0) return;
        int estimated = (agentSession.getEffectiveSystemPrompt().length()
                + ContextManager.getInstance(project).buildContextBlock().length()) / 4;
        updateTokenBar(estimated, 0, knownContextWindow);
    }

    /** Updates the token usage bar and label after each LLM response. */
    private void updateTokenBar(int promptTokens, int completionTokens, int contextLength) {
        int total = promptTokens + completionTokens;
        if (contextLength > 0) {
            int pct = (int) Math.min(100, (total * 100L) / contextLength);
            tokenBar.setValue(pct);
            // Colour the bar: green → yellow → red
            if (pct < 60)       tokenBar.setForeground(new Color(0x4C, 0xAF, 0x50));
            else if (pct < 85)  tokenBar.setForeground(new Color(0xFF, 0xB3, 0x00));
            else                tokenBar.setForeground(new Color(0xF4, 0x43, 0x36));
            tokenLabel.setText(String.format("Tokens: %,d / %,d (%d%%)", total, contextLength, pct));
        } else {
            tokenBar.setValue(0);
            tokenLabel.setText(total > 0
                    ? String.format("Tokens: %,d / ???", total)
                    : "Tokens: ???");
        }
    }

    // ------------------------------------------------------------------
    // Streaming display
    // ------------------------------------------------------------------

    private void appendStreamChunk(String batch) {
        streamBuffer.append(batch);
        if (streamingBubble == null) {
            // First chunk — create a plain JTextArea for smooth incremental display.
            // finalizeStream() replaces it with the full markdown component when done.
            streamingContentPane = new JTextArea();
            streamingContentPane.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            streamingContentPane.setForeground(ASSISTANT_FG);
            streamingContentPane.setBackground(ASSISTANT_BG);
            streamingContentPane.setOpaque(false);
            streamingContentPane.setEditable(false);
            streamingContentPane.setLineWrap(true);
            streamingContentPane.setWrapStyleWord(true);
            streamingContentPane.setAlignmentX(Component.LEFT_ALIGNMENT);
            streamingBubble = makeBubbleContainer(ASSISTANT_BG, 14);
            streamingBubbleInner = makeBubbleInner(streamingBubble);
            streamingBubbleInner.add(makeBubbleHeader("Copilot", new Color(0xCE, 0x91, 0x78)));
            streamingBubbleInner.add(streamingContentPane);
            addBubble(streamingBubble);
        }
        streamingContentPane.append(batch);
        streamingBubble.revalidate();
        scrollToBottom();
    }

    private void abortStream() {
        if (streamingBubble != null) {
            chatMessagesPanel.remove(streamingBubble);
            chatMessagesPanel.revalidate();
            chatMessagesPanel.repaint();
            // Remove the bubble+strut pair that addBubble tracked in this session
            if (!sessions.isEmpty()) {
                List<Component> comps = sessions.get(activeSessionIndex).components;
                int n = comps.size();
                if (n >= 2) { comps.remove(n - 1); comps.remove(n - 2); }
            }
        }
        streamBuffer.setLength(0);
        streamingBubble = null;
        streamingContentPane = null;
        streamingBubbleInner = null;
    }

    private void finalizeStream(String finalContent) {
        String content = streamBuffer.toString().trim();
        streamBuffer.setLength(0);

        if (content.isEmpty()) {
            streamingBubble = null;
            streamingContentPane = null;
            streamingBubbleInner = null;
            if (!finalContent.isEmpty() && !finalContent.startsWith("⏹") && !finalContent.startsWith("⚠️")) {
                appendAssistantMessage(finalContent);
            } else if (!finalContent.isEmpty()) {
                appendSystemMessage(finalContent);
            }
            return;
        }

        // Replace the live markdown pane with the final code-block-aware component
        if (streamingBubbleInner != null && streamingContentPane != null) {
            streamingBubbleInner.remove(streamingContentPane);
            JComponent richContent = createBubbleContent(content, ASSISTANT_FG, 12);
            richContent.setAlignmentX(Component.LEFT_ALIGNMENT);
            streamingBubbleInner.add(richContent);
            streamingBubble.revalidate();
            streamingBubble.repaint();
        }
        streamingBubble = null;
        streamingContentPane = null;
        streamingBubbleInner = null;
        scrollToBottom();

        // Track assistant reply and generate session title after the first exchange
        ChatSession cur = sessions.get(activeSessionIndex);
        cur.messageHistory.add(new CopilotSettings.SavedMessage("assistant", content));
        if (!cur.titled && cur.firstUserMessage != null) {
            int idx = activeSessionIndex;
            generateSessionTitle(idx, cur.firstUserMessage);
            cur.titled = true; // mark early to avoid double-firing
        }
    }

    /** Publishes a loading state change on the application message bus. */
    private static void publishLoading(boolean loading) {
        CopilotLoadingStateNotifier pub = ApplicationManager.getApplication()
                .getMessageBus().syncPublisher(CopilotLoadingStateNotifier.TOPIC);
        if (loading) pub.loadingStarted();
        else         pub.loadingStopped();
    }

    private void startThinkingAnimation(String baseText) {
        spinnerBaseText = baseText;
        spinnerFrame = 0;
        statusLabel.setText(spinnerText(SPINNER[0]));
        if (thinkingTimer != null) thinkingTimer.stop();
        thinkingTimer = new javax.swing.Timer(80, e -> {
            spinnerFrame = (spinnerFrame + 1) % SPINNER.length;
            statusLabel.setText(spinnerText(SPINNER[spinnerFrame]));
        });
        thinkingTimer.start();
    }

    private String spinnerText(String frame) {
        return spinnerBaseText.isEmpty() ? frame : frame + " " + spinnerBaseText + "…";
    }

    private void setSpinnerBase(String baseText) {
        spinnerBaseText = baseText;
    }

    private void stopThinkingAnimation() {
        if (thinkingTimer != null) {
            thinkingTimer.stop();
            thinkingTimer = null;
        }
        statusLabel.setText(" ");
    }

    // ------------------------------------------------------------------
    // Message bubbles
    // ------------------------------------------------------------------

    private void appendUserMessage(String text) { addBubble(makeUserBubble(text)); }
    private void appendAssistantMessage(String text) { addBubble(makeAssistantBubble(text)); }

    private static JPanel makeUserBubble(String text) {
        JPanel bubble = makeBubbleContainer(USER_BG, 14);
        JPanel inner  = makeBubbleInner(bubble);
        inner.add(makeBubbleHeader("You", ACCENT));
        inner.add(makeBubblePane(USER_FG, 12, escapeHtml(text).replace("\n", "<br>")));
        return bubble;
    }

    private static JPanel makeAssistantBubble(String text) {
        JPanel bubble = makeBubbleContainer(ASSISTANT_BG, 14);
        JPanel inner  = makeBubbleInner(bubble);
        inner.add(makeBubbleHeader("Copilot", new Color(0xCE, 0x91, 0x78)));
        JComponent content = createBubbleContent(text, ASSISTANT_FG, 12);
        inner.add(content);
        return bubble;
    }

    private void appendThinkingBlock(String thought) {
        JPanel bubble = makeBubbleContainer(new Color(0x20, 0x20, 0x20), 10);
        JPanel inner  = makeBubbleInner(bubble);
        inner.add(makeBubbleHeader("💭 Reasoning", STATUS_FG));
        inner.add(makeBubblePane(STATUS_FG, 11,
                "<i>" + escapeHtml(thought).replace("\n", "<br>") + "</i>"));
        addBubble(bubble);
    }

    private void appendThinkingChunk(String batch) {
        streamingThinkingBuffer.append(batch);
        if (streamingThinkingBubble == null) {
            streamingThinkingPane   = new JTextArea();
            streamingThinkingPane.setFont(new Font("Segoe UI", Font.ITALIC, 11));
            streamingThinkingPane.setForeground(STATUS_FG);
            streamingThinkingPane.setBackground(new Color(0x20, 0x20, 0x20));
            streamingThinkingPane.setOpaque(false);
            streamingThinkingPane.setEditable(false);
            streamingThinkingPane.setLineWrap(true);
            streamingThinkingPane.setWrapStyleWord(true);
            streamingThinkingPane.setAlignmentX(Component.LEFT_ALIGNMENT);
            streamingThinkingBubble = makeBubbleContainer(new Color(0x20, 0x20, 0x20), 10);
            JPanel inner = makeBubbleInner(streamingThinkingBubble);
            inner.add(makeBubbleHeader("💭 Reasoning", STATUS_FG));
            inner.add(streamingThinkingPane);
            addBubble(streamingThinkingBubble);
        }
        streamingThinkingPane.append(batch);
        streamingThinkingBubble.revalidate();
        scrollToBottom();
    }

    private void finalizeThinkingStream() {
        streamingThinkingBubble = null;
        streamingThinkingPane   = null;
        streamingThinkingBuffer.setLength(0);
    }

    private void appendToolMessage(String description) {
        JPanel bubble = makeBubbleContainer(new Color(0x1A, 0x22, 0x1A), 8);
        JPanel inner  = makeBubbleInner(bubble);
        inner.add(makeBubblePane(TOOL_FG, 11, escapeHtml(description)));
        addBubble(bubble);
    }

    private void appendToolErrorMessage(String toolName, String errorMessage) {
        JPanel bubble = makeBubbleContainer(new Color(0x2A, 0x14, 0x14), 8);
        JPanel inner  = makeBubbleInner(bubble);
        String html = "<b>" + escapeHtml(toolName) + ":</b> "
                + escapeHtml(errorMessage).replace("\n", "<br>");
        inner.add(makeBubblePane(new Color(0xC4, 0x6F, 0x6F), 10, html));
        addBubble(bubble);
    }

    private static final int MAX_DISPLAY_RESULT_CHARS = 20_000;

    private void appendToolResultMessage(String toolName, String result) {
        Color hdrFg = new Color(0x88, 0x99, 0xBB);

        JPanel bubble = makeBubbleContainer(new Color(0x1A, 0x1A, 0x22), 8);
        JPanel inner  = makeBubbleInner(bubble);

        // Truncate very large results for display — full content is stored in history
        String displayResult = result != null && result.length() > MAX_DISPLAY_RESULT_CHARS
                ? result.substring(0, MAX_DISPLAY_RESULT_CHARS)
                  + "\n... [display truncated — " + result.length() + " chars total]"
                : result;

        // Scrollable content area — hidden until the user expands
        JTextArea textArea = new JTextArea(displayResult);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        textArea.setBackground(new Color(0x12, 0x12, 0x1E));
        textArea.setForeground(hdrFg);
        textArea.setEditable(false);
        textArea.setLineWrap(false);
        textArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        int lineCount = (int) displayResult.lines().count();
        int lineH     = textArea.getFontMetrics(textArea.getFont()).getHeight();
        int scrollH   = Math.min(20, Math.max(3, lineCount + 1)) * lineH + 18;

        JScrollPane contentScroll = new JScrollPane(textArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        contentScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR));
        contentScroll.setPreferredSize(new Dimension(0, scrollH));
        contentScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, scrollH));
        contentScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentScroll.setVisible(false);

        // Clickable header row — starts collapsed
        JLabel toggleLabel = new JLabel(toolName, AllIcons.General.ArrowRight, SwingConstants.LEFT);
        toggleLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        toggleLabel.setForeground(hdrFg);
        toggleLabel.setIconTextGap(6);
        toggleLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        boolean[] expanded = {false};
        toggleLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                expanded[0] = !expanded[0];
                toggleLabel.setIcon(expanded[0] ? AllIcons.General.ArrowDown : AllIcons.General.ArrowRight);
                contentScroll.setVisible(expanded[0]);
                chatMessagesPanel.revalidate();
                chatMessagesPanel.repaint();
            }
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                toggleLabel.setForeground(new Color(0xAA, 0xBB, 0xDD));
            }
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                toggleLabel.setForeground(hdrFg);
            }
        });

        inner.add(toggleLabel);
        inner.add(contentScroll);
        addBubble(bubble);
    }

    private void appendEditResultMessage(String filePath, String oldCode, String newCode) {
        Color hdrFg    = new Color(0x88, 0x99, 0xBB);
        Color removeBg = new Color(0x3A, 0x14, 0x14);
        Color removeFg = new Color(0xFF, 0xA0, 0xA0);
        Color addBg    = new Color(0x14, 0x2A, 0x14);
        Color addFg    = new Color(0x9C, 0xE5, 0x9C);

        JPanel bubble = makeBubbleContainer(new Color(0x1A, 0x1A, 0x22), 8);
        JPanel inner  = makeBubbleInner(bubble);

        int oldLines = oldCode.split("\n", -1).length;
        int newLines = newCode.split("\n", -1).length;
        String delta = newLines > oldLines ? "+" + (newLines - oldLines)
                     : newLines < oldLines ? "-" + (oldLines - newLines)
                     : "±0";
        String label = filePath.isEmpty() ? "replace_in_file" : filePath + "  (" + delta + ")";

        // Side-by-side diff panel — hidden until expanded
        JPanel diffPanel = new JPanel(new GridLayout(1, 2, 4, 0));
        diffPanel.setOpaque(false);
        diffPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        diffPanel.setVisible(false);

        diffPanel.add(makeDiffPane("Before", oldCode, removeBg, removeFg));
        diffPanel.add(makeDiffPane("After",  newCode, addBg,    addFg));

        int lineH   = new JTextArea().getFontMetrics(new Font(Font.MONOSPACED, Font.PLAIN, 11)).getHeight();
        int maxVis  = Math.min(20, Math.max(oldLines, newLines) + 2);
        int panelH  = maxVis * lineH + 24;
        diffPanel.setPreferredSize(new Dimension(0, panelH));
        diffPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panelH));

        // Clickable header
        JLabel toggleLabel = new JLabel(label, AllIcons.General.ArrowRight, SwingConstants.LEFT);
        toggleLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        toggleLabel.setForeground(hdrFg);
        toggleLabel.setIconTextGap(6);
        toggleLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        boolean[] expanded = {false};
        toggleLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                expanded[0] = !expanded[0];
                toggleLabel.setIcon(expanded[0] ? AllIcons.General.ArrowDown : AllIcons.General.ArrowRight);
                diffPanel.setVisible(expanded[0]);
                chatMessagesPanel.revalidate();
                chatMessagesPanel.repaint();
            }
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { toggleLabel.setForeground(new Color(0xAA, 0xBB, 0xDD)); }
            @Override public void mouseExited(java.awt.event.MouseEvent e)  { toggleLabel.setForeground(hdrFg); }
        });

        inner.add(toggleLabel);
        inner.add(diffPanel);
        addBubble(bubble);
    }

    private static JScrollPane makeDiffPane(String title, String code, Color bg, Color fg) {
        JTextArea area = new JTextArea(code);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        area.setBackground(bg);
        area.setForeground(fg);
        area.setEditable(false);
        area.setLineWrap(false);
        area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JScrollPane scroll = new JScrollPane(area,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BORDER_COLOR), title,
                javax.swing.border.TitledBorder.LEFT, javax.swing.border.TitledBorder.TOP,
                new Font("Segoe UI", Font.PLAIN, 10), fg));
        return scroll;
    }

    private void appendSystemMessage(String text) {
        addBubble(makePlainBubble(text, STATUS_FG, BG, 11, true));
    }

    private void appendCompressionNotice(int originalTokens, int compressedTokens, String snapshotPath) {
        int saved = originalTokens - compressedTokens;
        int pct   = originalTokens > 0 ? (saved * 100 / originalTokens) : 0;
        String msg = String.format(
                "🗜 Context compressed: ~%,d → ~%,d tokens (%d%% reduction)",
                originalTokens, compressedTokens, pct);
        if (snapshotPath != null) msg += "  |  Snapshot: " + snapshotPath;
        appendSystemMessage(msg);
    }

    // ------------------------------------------------------------------
    // Bubble factory helpers
    // ------------------------------------------------------------------

    /** Adds a bubble to the messages panel and re-flows layout. */
    private void addBubble(JPanel bubble) {
        bubble.setAlignmentX(Component.LEFT_ALIGNMENT);
        Component strut = Box.createVerticalStrut(4);
        chatMessagesPanel.add(bubble);
        chatMessagesPanel.add(strut);
        chatMessagesPanel.revalidate();
        scrollToBottom();
        if (!sessions.isEmpty()) {
            List<Component> comps = sessions.get(activeSessionIndex).components;
            comps.add(bubble);
            comps.add(strut);
        }
    }

    /**
     * Creates the outer rounded-corner bubble panel.
     * {@link #makeBubbleInner} must be called to get the content panel inside it.
     */
    private static JPanel makeBubbleContainer(Color bgColor, int arc) {
        JPanel bubble = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bgColor);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.dispose();
            }
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        bubble.setOpaque(false);
        return bubble;
    }

    /** Returns the inner content panel of a bubble (padding applied here). */
    private static JPanel makeBubbleInner(JPanel bubble) {
        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setOpaque(false);
        inner.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        bubble.add(inner, BorderLayout.CENTER);
        return inner;
    }

    /** Small bold label used as the speaker name inside a bubble. */
    private static JLabel makeBubbleHeader(String name, Color color) {
        JLabel lbl = new JLabel("<html><b><font color='" + toHex(color) + "'>" + name + "</font></b></html>");
        lbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbl.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        return lbl;
    }

    /**
     * Creates a {@link JEditorPane} with HTML content, sized to correctly fill its parent
     * width (the JEditorPane queries its View at the actual parent width inside
     * {@code getPreferredSize} so BoxLayout gets the right height).
     */
    private static JEditorPane makeBubblePane(Color textColor, int fontSize, String contentHtml) {
        JEditorPane pane = makeBubblePane(textColor, fontSize);
        pane.setText(paneHtml(toHex(textColor), fontSize, contentHtml));
        return pane;
    }

    /** Creates an empty bubble pane (content set later, used for streaming). */
    private static JEditorPane makeBubblePane(Color textColor, int fontSize) {
        JEditorPane pane = new JEditorPane() {
            private int prevWidth = 0;

            /**
             * When the layout manager gives us our actual width, check if it changed.
             * If so, trigger a re-layout so our preferred height is recomputed at the
             * new width — this is what makes text re-wrap on window resize.
             */
            @Override
            public void setBounds(int x, int y, int width, int height) {
                super.setBounds(x, y, width, height);
                if (width > 0 && width != prevWidth) {
                    prevWidth = width;
                    SwingUtilities.invokeLater(() -> {
                        if (getParent() != null) getParent().revalidate();
                    });
                }
            }

            @Override
            public Dimension getPreferredSize() {
                // Prefer the actual rendered width; fall back to parent width during
                // the first layout pass when our own width is not yet known.
                int w = getWidth() > 10 ? getWidth()
                        : (getParent() != null ? getParent().getWidth() : 0);
                if (w > 10) {
                    try {
                        View root = getUI().getRootView(this);
                        root.setSize(w, Short.MAX_VALUE);
                        int h = (int) root.getPreferredSpan(View.Y_AXIS) + 2;
                        return new Dimension(w, h);
                    } catch (Exception ignored) {}
                }
                return super.getPreferredSize();
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        pane.setEditorKit(new HTMLEditorKit());
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.setFont(new Font("Segoe UI", Font.PLAIN, fontSize));
        pane.setAlignmentX(Component.LEFT_ALIGNMENT);
        return pane;
    }

    /** Creates a simple italic plain-text bubble (no header, no rounded background). */
    private static JPanel makePlainBubble(String text, Color color, Color bg, int fontSize, boolean italic) {
        JPanel bubble = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        bubble.setOpaque(false);
        JEditorPane pane = makeBubblePane(color, fontSize);
        String content = italic
                ? "<i>" + escapeHtml(text).replace("\n", "<br>") + "</i>"
                : escapeHtml(text).replace("\n", "<br>");
        pane.setText(paneHtml(toHex(color), fontSize, content));
        bubble.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        bubble.add(pane, BorderLayout.CENTER);
        return bubble;
    }

    /** Minimal HTML wrapper for a bubble pane's content. */
    private static String paneHtml(String colorHex, int fontSize, String bodyContent) {
        return "<html><body style='font-family:sans-serif; font-size:" + fontSize + "px; "
                + "color:" + colorHex + "; margin:0; padding:0;'>"
                + bodyContent + "</body></html>";
    }

    /**
     * Splits markdown text into alternating text / code-block segments and returns
     * the appropriate Swing component:
     * <ul>
     *   <li>No code blocks → a single {@link JEditorPane} that word-wraps.</li>
     *   <li>Code blocks present → a {@link JPanel} (BoxLayout Y) mixing
     *       {@link JEditorPane} for prose and a {@link JScrollPane}/{@link JTextArea}
     *       for each code block (horizontal scrollbar enabled).</li>
     * </ul>
     */
    private static JComponent createBubbleContent(String markdown, Color textColor, int fontSize) {
        Pattern codePattern = Pattern.compile("```(?:[a-zA-Z0-9_+\\-.]*)?\n?([\\s\\S]*?)```",
                Pattern.MULTILINE);
        Matcher m = codePattern.matcher(markdown);

        if (!m.find()) {
            // No code blocks — simple word-wrapped pane
            return makeBubblePane(textColor, fontSize, inlineMarkdown(markdown));
        }

        // Mixed content — build a vertical panel
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setAlignmentX(Component.LEFT_ALIGNMENT);

        m.reset();
        int lastEnd = 0;
        while (m.find()) {
            // Prose before the code block
            String prose = markdown.substring(lastEnd, m.start()).trim();
            if (!prose.isEmpty()) {
                JEditorPane pp = makeBubblePane(textColor, fontSize, inlineMarkdown(prose));
                pp.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
                content.add(pp);
            }

            // Code block — scrollable JTextArea
            String code = m.group(1).stripTrailing();
            JTextArea codeArea = new JTextArea(code);
            codeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize - 1));
            codeArea.setBackground(CODE_BG);
            codeArea.setForeground(new Color(0x9C, 0xDC, 0xFE));
            codeArea.setCaretColor(new Color(0x9C, 0xDC, 0xFE));
            codeArea.setEditable(false);
            codeArea.setLineWrap(false);
            codeArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

            JScrollPane codeScroll = new JScrollPane(codeArea,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            codeScroll.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
            codeScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            // Height: fit content up to ~12 visible lines
            int lineH  = codeArea.getFontMetrics(codeArea.getFont()).getHeight();
            int lines  = Math.min(12, (int) code.lines().count() + 1);
            int height = lines * lineH + 20;
            codeScroll.setPreferredSize(new Dimension(Short.MAX_VALUE, height));
            codeScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));

            JPanel codeWrapper = new JPanel(new BorderLayout()) {
                @Override
                public Dimension getMaximumSize() {
                    return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
                }
            };
            codeWrapper.setOpaque(false);
            codeWrapper.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));
            codeWrapper.add(codeScroll, BorderLayout.CENTER);
            codeWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(codeWrapper);

            lastEnd = m.end();
        }

        // Remaining prose after the last code block
        String trailing = markdown.substring(lastEnd).trim();
        if (!trailing.isEmpty()) {
            JEditorPane pp = makeBubblePane(textColor, fontSize, inlineMarkdown(trailing));
            pp.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
            content.add(pp);
        }

        return content;
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            if (!autoScroll) return;
            programmaticScroll = true;
            JScrollBar sb = chatScroll.getVerticalScrollBar();
            sb.setValue(sb.getMaximum());
            programmaticScroll = false;
        });
    }

    // ------------------------------------------------------------------
    // Markdown → HTML  (commonmark + GFM extensions)
    // ------------------------------------------------------------------

    private static final org.commonmark.parser.Parser MD_PARSER;
    private static final org.commonmark.renderer.html.HtmlRenderer MD_RENDERER;

    static {
        List<org.commonmark.Extension> extensions = List.of(
                org.commonmark.ext.gfm.tables.TablesExtension.create(),
                org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create(),
                org.commonmark.ext.task.list.items.TaskListItemsExtension.create(),
                org.commonmark.ext.autolink.AutolinkExtension.create()
        );
        MD_PARSER   = org.commonmark.parser.Parser.builder().extensions(extensions).build();
        MD_RENDERER = org.commonmark.renderer.html.HtmlRenderer.builder().extensions(extensions).build();
    }

    /** Converts markdown to an HTML fragment suitable for Swing's HTMLEditorKit. */
    private static String inlineMarkdown(String text) {
        return MD_RENDERER.render(MD_PARSER.parse(text));
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------
    // Config diagnostics
    // ------------------------------------------------------------------

    private static String diagnoseMissingConfig(copilot.CopilotSettings cfg) {
        if (cfg.getApiEndpoint() == null || cfg.getApiEndpoint().trim().isEmpty()) {
            return "No API endpoint configured. Please set your API Endpoint (e.g. http://127.0.0.1:11434/v1/chat/completions) and Model.";
        }
        if (cfg.getModel() == null || cfg.getModel().trim().isEmpty()) {
            return "No model configured. Please set the Model name (e.g. qwen3-coder-next).";
        }
        return "Configuration incomplete. Please check your settings.";
    }

    // ------------------------------------------------------------------
    // Debug context viewer
    // ------------------------------------------------------------------

    private void showSystemContextDialog() {
        String initial = agentSession.getFullContextDump();

        JTextArea textArea = new JTextArea(unescape(initial));
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setBackground(new Color(0x1E, 0x1E, 0x1E));
        textArea.setForeground(new Color(0xD4, 0xD4, 0xD4));
        textArea.setCaretColor(new Color(0xD4, 0xD4, 0xD4));
        textArea.setEditable(false);
        textArea.setLineWrap(false);
        textArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JScrollPane scroll = new JScrollPane(textArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        scroll.getViewport().setBackground(new Color(0x1E, 0x1E, 0x1E));
        scroll.setPreferredSize(new Dimension(800, 600));

        JLabel statusLabel = new JLabel(String.format("  %,d chars — live", initial.length()));
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        statusLabel.setForeground(STATUS_FG);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(OUTER_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "System Context — Live", java.awt.Dialog.ModalityType.MODELESS);
        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        // Poll for changes every 500 ms; update text area only when content actually changes
        final String[] last = {initial};
        javax.swing.Timer refreshTimer = new javax.swing.Timer(500, e -> {
            String current = agentSession.getFullContextDump();
            if (!current.equals(last[0])) {
                last[0] = current;
                JScrollBar vBar = scroll.getVerticalScrollBar();
                JScrollBar hBar = scroll.getHorizontalScrollBar();
                int vVal = vBar.getValue();
                int hVal = hBar.getValue();
                textArea.setText(unescape(current));
                SwingUtilities.invokeLater(() -> {
                    vBar.setValue(vVal);
                    hBar.setValue(hVal);
                });
                statusLabel.setText(String.format("  %,d chars — live", current.length()));
            }
        });
        refreshTimer.start();

        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                refreshTimer.stop();
            }
        });

        dialog.setVisible(true);
    }

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    /**
     * Wraps {@code content} in a panel that:
     * <ul>
     *   <li>paints a rounded-rectangle fill with {@code fill} colour</li>
     *   <li>clips all child painting to the same rounded rectangle (true corner clipping)</li>
     *   <li>draws a 1-px {@code border} colour outline on top</li>
     * </ul>
     */
    private static JPanel roundedWrap(JComponent content, Color fill, Color border, int arc) {
        JPanel wrapper = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.dispose();
            }

            @Override
            protected void paintChildren(Graphics g) {
                // Clip children to the rounded shape so corners are truly clipped
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setClip(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc));
                super.paintChildren(g2);
                g2.dispose();
                // Draw border outline on top of children
                Graphics2D g3 = (Graphics2D) g.create();
                g3.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g3.setColor(border);
                g3.setStroke(new BasicStroke(1f));
                g3.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                g3.dispose();
            }
        };
        wrapper.setOpaque(false);
        wrapper.add(content, BorderLayout.CENTER);
        return wrapper;
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** Converts JSON string escape sequences to their real characters for display. */
    private static String unescape(String json) {
        return json.replace("\\n", "\n")
                   .replace("\\t", "\t")
                   .replace("\\r", "")
                   .replace("\\\"", "\"")
                   .replace("\\\\", "\\");
    }

    // ------------------------------------------------------------------
    // Mode selector
    // ------------------------------------------------------------------

    private JComboBox<String> buildModeButton() {
        JComboBox<String> combo = new JComboBox<>();  // empty model → native popup has nothing to show
        combo.setFont(agentCombo.getFont());
        combo.setToolTipText("Select chat mode");
        combo.setPrototypeDisplayValue("Design: Configuration");  // width sizing hint

        // Always render the current mode name in the button; the list is intentionally empty
        combo.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel lbl = new JLabel(index == -1 ? currentMode.displayName() : "");
            lbl.setFont(agentCombo.getFont());
            return lbl;
        });

        combo.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                // Dismiss the empty native popup and show our hierarchical one instead
                SwingUtilities.invokeLater(() -> { combo.hidePopup(); showModePopup(); });
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        return combo;
    }

    private void showModePopup() {
        DefaultActionGroup root = new DefaultActionGroup();

        DefaultActionGroup design = new DefaultActionGroup("Design", true);
        design.getTemplatePresentation().setIcon(AllIcons.Actions.Edit);
        for (ChatMode m : new ChatMode[]{
                ChatMode.DESIGN_FUNDAMENTALS, ChatMode.DESIGN_INTERFACES,
                ChatMode.DESIGN_ARCHITECTURE, ChatMode.DESIGN_WORKFLOWS,
                ChatMode.DESIGN_STORAGE, ChatMode.DESIGN_CONFIGURATION,
                ChatMode.DESIGN_API, ChatMode.DESIGN_CONVENTIONS,
                ChatMode.DESIGN_DECISIONS}) {
            design.add(modeAction(m));
        }
        root.add(design);

        DefaultActionGroup plan = new DefaultActionGroup("Plan", true);
        plan.getTemplatePresentation().setIcon(AllIcons.General.Information);
        plan.add(modeAction(ChatMode.PLAN_CREATE));
        root.add(plan);

        DefaultActionGroup action = new DefaultActionGroup("Action", true);
        action.getTemplatePresentation().setIcon(AllIcons.Actions.Execute);
        action.add(modeAction(ChatMode.ACTION_EXECUTE));
        root.add(action);

        DefaultActionGroup debug = new DefaultActionGroup("Debug", true);
        debug.getTemplatePresentation().setIcon(AllIcons.Actions.StartDebugger);
        debug.add(modeAction(ChatMode.DEBUG_INVESTIGATE));
        root.add(debug);

        root.addSeparator();
        root.add(modeAction(ChatMode.FREE_DEVELOP));

        ActionManager.getInstance()
                .createActionPopupMenu("KalynxModeSelector", root)
                .getComponent()
                .show(modeButton, 0, modeButton.getHeight());
    }

    // Transparent icon with the same dimensions as Checked — keeps the icon column stable.
    private static final javax.swing.Icon EMPTY_ICON = new javax.swing.Icon() {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {}
        @Override public int getIconWidth()  { return AllIcons.Actions.Checked.getIconWidth(); }
        @Override public int getIconHeight() { return AllIcons.Actions.Checked.getIconHeight(); }
    };

    private AnAction modeAction(ChatMode mode) {
        AnAction action = new AnAction(mode.label) {
            @Override
            public void actionPerformed(@org.jetbrains.annotations.NotNull AnActionEvent e) {
                currentMode = mode;
                modeButton.repaint();
                agentSession.setModePrompt(mode.buildSystemPrompt());
                if (mode.name().startsWith("DESIGN_")) {
                    DesignScaffolder.scaffoldIfNeeded(project);
                }
            }
            @Override
            public void update(@org.jetbrains.annotations.NotNull AnActionEvent e) {
                e.getPresentation().setIcon(currentMode == mode ? AllIcons.Actions.Checked : EMPTY_ICON);
            }
            @Override
            public @org.jetbrains.annotations.NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
        action.getTemplatePresentation().setIcon(EMPTY_ICON);
        return action;
    }

    // ------------------------------------------------------------------
    // File context chips
    // ------------------------------------------------------------------

    /** Adds a file or folder via drag-drop (tracked for full-content prepend in sendMessage). */
    private void addContextFile(File f) {
        if (contextFiles.contains(f)) return;
        contextFiles.add(f);
        ContextManager.getInstance(project).addEntry(f); // listener fires refreshChips
        refreshChips(); // immediate visual feedback before snapshot generation completes
    }

    /** Rebuilds the chips panel to exactly mirror ContextManager's current entries. */
    private void refreshChips() {
        List<ContextManager.WatchedEntry> entries = ContextManager.getInstance(project).getEntries();
        chipsPanel.removeAll();
        chipsPanel.setVisible(!entries.isEmpty());
        for (ContextManager.WatchedEntry entry : entries) {
            chipsPanel.add(makeChip(entry));
        }
        chipsPanel.revalidate();
        chipsPanel.repaint();
    }

    /** Removes an entry from both ContextManager and the local drag-drop tracking list. */
    private void removeContextEntry(ContextManager.WatchedEntry entry) {
        String basePath = project.getBasePath();
        if (basePath != null) {
            String abs = (basePath + "/" + entry.relativePath).replace("\\", "/");
            contextFiles.removeIf(f -> f.getAbsolutePath().replace("\\", "/").equals(abs));
        }
        ContextManager.getInstance(project).removeEntry(entry); // triggers listener -> refreshChips
    }

    private JPanel makeChip(ContextManager.WatchedEntry entry) {
        boolean aiOwned = entry.source == ContextManager.Source.AI;
        Color chipBg = aiOwned ? new Color(0x35, 0x28, 0x00) : new Color(0x2A, 0x2A, 0x3C);
        Color chipFg = aiOwned ? new Color(0xD4, 0xA0, 0x3C) : new Color(0xA0, 0xB8, 0xD8);

        JPanel chip = new JPanel(new BorderLayout(3, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(chipBg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(BORDER_COLOR);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
            }
            @Override
            public Dimension getMaximumSize() { return getPreferredSize(); }
        };
        chip.setOpaque(false);
        chip.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 4));

        String displayName = entry.displayName();
        String name = displayName.length() > 20 ? displayName.substring(0, 18) + "…" : displayName;
        JLabel nameLabel = new JLabel(name, entry.isFolder ? AllIcons.Nodes.Folder : AllIcons.FileTypes.Text, SwingConstants.LEFT);
        nameLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        nameLabel.setForeground(chipFg);
        nameLabel.setIconTextGap(3);
        nameLabel.setToolTipText(entry.relativePath);

        JLabel removeLabel = new JLabel("×");
        removeLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        removeLabel.setForeground(new Color(0x60, 0x60, 0x60));
        removeLabel.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 0));
        removeLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        removeLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e)  { removeContextEntry(entry); }
            @Override public void mouseEntered(java.awt.event.MouseEvent e)  { removeLabel.setForeground(new Color(0xCC, 0x55, 0x55)); }
            @Override public void mouseExited(java.awt.event.MouseEvent e)   { removeLabel.setForeground(new Color(0x60, 0x60, 0x60)); }
        });

        chip.add(nameLabel, BorderLayout.CENTER);
        chip.add(removeLabel, BorderLayout.EAST);
        return chip;
    }

    /** Reads file or folder contents into a context block (called on a background thread). */
    private static String buildFileContext(File f) {
        if (f.isFile()) {
            try {
                String content = Files.readString(f.toPath());
                if (content.length() > 8_000) content = content.substring(0, 8_000) + "\n... [truncated]";
                return "### File: " + f.getAbsolutePath() + "\n```\n" + content + "\n```\n\n";
            } catch (IOException e) {
                return "### File: " + f.getAbsolutePath() + "\n(Could not read: " + e.getMessage() + ")\n\n";
            }
        }
        if (f.isDirectory()) {
            StringBuilder sb = new StringBuilder("### Directory: " + f.getAbsolutePath() + "\n");
            try (var stream = Files.walk(f.toPath())) {
                stream.filter(Files::isRegularFile)
                      .limit(30)
                      .forEach(p -> {
                          try {
                              String content = Files.readString(p);
                              if (content.length() > 2_000)
                                  content = content.substring(0, 2_000) + "\n... [truncated]";
                              sb.append("#### ").append(p).append("\n```\n")
                                .append(content).append("\n```\n\n");
                          } catch (IOException ignored) {}
                      });
            } catch (IOException e) {
                sb.append("(Could not list directory: ").append(e.getMessage()).append(")\n");
            }
            return sb.toString();
        }
        return "";
    }

    /** Holds one independent chat conversation: its history, UI bubbles, and persistence data. */
    private class ChatSession {
        String name;
        boolean titled = false;
        String firstUserMessage = null;
        AgentSession agentSession;
        final List<Component> components = new ArrayList<>();
        final List<CopilotSettings.SavedMessage> messageHistory = new ArrayList<>();

        // Per-session editable system-prompt sections. Initialised from static defaults
        // on first creation; survive agent switches within the same session.
        String dynamicContextSection  = AgentSession.defaultDynamicContextSection();
        String agenticWorkflowSection = AgentSession.defaultAgenticWorkflowSection();
        String guidelinesSection      = AgentSession.defaultGuidelinesSection();

        // Per-session agent plan — saved/restored when switching sessions.
        String plan = null;

        ChatSession(String name) {
            this.name = name;
            this.agentSession = new AgentSession(project);
        }
    }

    private static class ScrollablePanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d)  { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 100; }
        @Override public boolean getScrollableTracksViewportWidth()  { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /**
     * FlowLayout variant that correctly reports a multi-row preferred height when
     * the container is narrower than a single row of components. Standard FlowLayout
     * always returns single-row height, which prevents BorderLayout.NORTH from
     * growing when chips wrap.
     */
    private static class WrapLayout extends FlowLayout {
        WrapLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            return layoutSize(target, false);
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getSize().width;
                if (targetWidth == 0) targetWidth = Integer.MAX_VALUE;

                Insets insets = target.getInsets();
                int maxWidth  = targetWidth - insets.left - insets.right - getHgap() * 2;
                Dimension dim = new Dimension(0, 0);
                int rowWidth  = 0;
                int rowHeight = 0;

                for (int i = 0; i < target.getComponentCount(); i++) {
                    Component m = target.getComponent(i);
                    if (!m.isVisible()) continue;
                    Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                    if (rowWidth > 0 && rowWidth + getHgap() + d.width > maxWidth) {
                        addRow(dim, rowWidth, rowHeight);
                        rowWidth  = 0;
                        rowHeight = 0;
                    }
                    if (rowWidth > 0) rowWidth += getHgap();
                    rowWidth  += d.width;
                    rowHeight  = Math.max(rowHeight, d.height);
                }
                addRow(dim, rowWidth, rowHeight);

                dim.width  += insets.left + insets.right + getHgap() * 2;
                dim.height += insets.top  + insets.bottom + getVgap() * 2;
                return dim;
            }
        }

        private void addRow(Dimension dim, int rowWidth, int rowHeight) {
            dim.width = Math.max(dim.width, rowWidth);
            if (dim.height > 0) dim.height += getVgap();
            dim.height += rowHeight;
        }
    }
}

