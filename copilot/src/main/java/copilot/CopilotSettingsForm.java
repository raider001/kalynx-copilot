package copilot;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.OnePixelSplitter;
import copilot.ui.LoadingButton;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import java.awt.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static java.awt.GridBagConstraints.*;

/**
 * Settings dialog for managing multiple AI agents.
 *
 * <h3>Data-flow contract</h3>
 * <ul>
 *   <li>On open: live settings are deep-copied into {@code editAgents}.
 *       Nothing is written back until OK.</li>
 *   <li>{@code lastEditedIndex} tracks which agent is currently shown in the
 *       editor.  When a different list item is selected we flush the editor
 *       into {@code editAgents[lastEditedIndex]} <em>before</em> loading the
 *       new one — so we always save into the agent that the user just left,
 *       not the one they are switching to.</li>
 *   <li>Test Connection reads directly from the editor fields; it never
 *       touches {@code editAgents} or live settings.</li>
 *   <li>OK flushes the current editor then writes all of {@code editAgents}
 *       to live settings and fires the message-bus event.</li>
 * </ul>
 */
public class CopilotSettingsForm extends DialogWrapper {

    // --- list (left) ---
    private DefaultListModel<AgentConfig> listModel;
    private JList<AgentConfig> agentList;
    private LoadingButton addBtn;
    private LoadingButton removeBtn;
    private LoadingButton copyBtn;

    // --- editor fields (right) ---
    private JTextField     nameField;
    private JTextField     apiEndpointField;
    private JPasswordField apiKeyField;
    private JTextField     modelField;
    private JSpinner       maxIterationsSpinner;
    private JSpinner       maxOutputTokensSpinner;
    private JSpinner       requestTimeoutSpinner;
    private JComboBox<String> toolChoiceCombo;
    private JCheckBox      parseTextToolCallsCheckbox;
    private JSpinner       compressionThresholdSpinner;
    private JCheckBox      retainToolHistoryCheckbox;
    private JCheckBox      showDynamicContextTabCheckbox;
    private JTextArea      systemPromptArea;
    private LoadingButton  testBtn;
    private JLabel         statusLabel;

    /** All editable agents — deep copies; only persisted on OK. */
    private final List<AgentConfig> editAgents = new ArrayList<>();

    /**
     * Index of the agent whose data is currently shown in the editor fields.
     * -1 means nothing has been loaded yet (prevents spurious initial flush).
     */
    private int lastEditedIndex = -1;

    /**
     * Set to {@code true} during programmatic list mutations (add/remove) so
     * the selection listener ignores those intermediate events.
     */
    private boolean suppressSelectionEvents = false;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public CopilotSettingsForm() {
        super(true);
        setTitle("Kalynx Copilot — Agent Settings");

        CopilotSettings settings = CopilotSettings.getInstance();
        for (AgentConfig a : settings.agents) editAgents.add(a.copy());
        int initialSel = Math.max(0, Math.min(settings.activeAgentIndex, editAgents.size() - 1));

        init();  // builds UI — fields exist but are empty at this point

        CopilotSettings globalSettings = CopilotSettings.getInstance();
        retainToolHistoryCheckbox.setSelected(globalSettings.retainToolHistory);
        showDynamicContextTabCheckbox.setSelected(globalSettings.showDynamicContextTab);

        // Load the active agent into the editor without an unwanted flush.
        // suppressSelectionEvents ensures the listener won't try to save empty
        // field values over the agent we're about to display.
        suppressSelectionEvents = true;
        agentList.setSelectedIndex(initialSel);
        suppressSelectionEvents = false;

        if (!editAgents.isEmpty()) {
            loadAgentIntoEditor(initialSel);
        }
    }

    // -----------------------------------------------------------------------
    // Dialog layout
    // -----------------------------------------------------------------------

    /**
     * Returns a {@link LoadingButton} for every dialog action (OK, Cancel, …)
     * so those buttons also pulse during a test-connection run.
     */
    @Override
    protected @NotNull JButton createJButtonForAction(@NotNull Action action) {
        LoadingButton btn = new LoadingButton((String) action.getValue(Action.NAME));
        btn.setAction(action);
        return btn;
    }

    @Override
    protected JComponent createCenterPanel() {
        OnePixelSplitter split = new OnePixelSplitter(false, 200f / 760f);
        split.setFirstComponent(buildListPanel());
        split.setSecondComponent(buildEditorPanel());
        split.setPreferredSize(new Dimension(760, 440));

        JPanel outer = new JPanel(new BorderLayout(0, 6));
        outer.add(split, BorderLayout.CENTER);
        outer.add(buildGlobalSettingsPanel(), BorderLayout.SOUTH);
        return outer;
    }

    private JPanel buildGlobalSettingsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Global Settings"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = WEST; c.fill = HORIZONTAL; c.weightx = 1.0; c.gridwidth = 2;

        c.gridx = 0; c.gridy = 0;
        retainToolHistoryCheckbox = new JCheckBox("Keep tool calls in context (useful for debugging)");
        retainToolHistoryCheckbox.setToolTipText(
                "When enabled, tool-call and tool-result messages are kept in the conversation " +
                "so the model can see its previous attempts. Increases context usage.");
        panel.add(retainToolHistoryCheckbox, c);

        c.gridy = 1;
        showDynamicContextTabCheckbox = new JCheckBox("Show Dynamic Context Management tab");
        showDynamicContextTabCheckbox.setToolTipText(
                "When enabled, the Dynamic Context Management tab appears in the Copilot tool window.");
        panel.add(showDynamicContextTabCheckbox, c);

        return panel;
    }

    private JPanel buildListPanel() {
        listModel = new DefaultListModel<>();
        for (AgentConfig a : editAgents) listModel.addElement(a);

        agentList = new JList<>(listModel);
        agentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        agentList.setCellRenderer(new AgentCellRenderer());
        agentList.addListSelectionListener(this::onListSelectionChanged);

        JScrollPane scroll = new JScrollPane(agentList);
        scroll.setBorder(BorderFactory.createTitledBorder("Agents"));

        addBtn    = new LoadingButton("+");
        removeBtn = new LoadingButton("−");
        copyBtn   = new LoadingButton("Copy");
        addBtn.setToolTipText("Add a new blank agent");
        removeBtn.setToolTipText("Remove the selected agent");
        copyBtn.setToolTipText("Duplicate the selected agent");
        addBtn.addActionListener(e -> addAgent());
        removeBtn.addActionListener(e -> removeAgent());
        copyBtn.addActionListener(e -> copyAgent());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        btnRow.add(addBtn);
        btnRow.add(removeBtn);
        btnRow.add(copyBtn);

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        panel.setPreferredSize(new Dimension(195, 0));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(btnRow, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildEditorPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);

        int row = 0;

        // Name
        c.gridx = 0; c.gridy = row; c.anchor = WEST; c.fill = NONE; c.weightx = 0;
        panel.add(new JLabel("Name:"), c);
        c.gridx = 1; c.fill = HORIZONTAL; c.weightx = 1.0;
        nameField = new JTextField(32);
        nameField.setToolTipText("Display label shown in the chat dropdown");
        panel.add(nameField, c);
        row++;

        // Endpoint
        c.gridx = 0; c.gridy = row; c.fill = NONE; c.weightx = 0;
        panel.add(new JLabel("API Endpoint:"), c);
        c.gridx = 1; c.fill = HORIZONTAL; c.weightx = 1.0;
        apiEndpointField = new JTextField(32);
        apiEndpointField.setToolTipText("e.g. http://127.0.0.1:11434/v1/chat/completions");
        panel.add(apiEndpointField, c);
        row++;

        // API Key
        c.gridx = 0; c.gridy = row; c.fill = NONE; c.weightx = 0;
        panel.add(new JLabel("API Key (optional):"), c);
        c.gridx = 1; c.fill = HORIZONTAL; c.weightx = 1.0;
        apiKeyField = new JPasswordField(32);
        apiKeyField.setToolTipText("Leave blank for local Ollama / LM Studio");
        panel.add(apiKeyField, c);
        row++;

        // Model
        c.gridx = 0; c.gridy = row; c.fill = NONE; c.weightx = 0;
        panel.add(new JLabel("Model:"), c);
        c.gridx = 1; c.fill = HORIZONTAL; c.weightx = 1.0;
        modelField = new JTextField(32);
        modelField.setToolTipText("e.g. qwen3-coder-next  or  gpt-4o");
        panel.add(modelField, c);
        row++;

        // Max iterations
        c.gridx = 0; c.gridy = row; c.fill = NONE; c.weightx = 0; c.anchor = WEST;
        panel.add(new JLabel("Max Iterations:"), c);
        c.gridx = 1; c.fill = NONE; c.weightx = 0;
        maxIterationsSpinner = new JSpinner(new SpinnerNumberModel(100, 1, 10_000, 10));
        maxIterationsSpinner.setToolTipText("Maximum agentic loop iterations before the agent gives up.");
        panel.add(maxIterationsSpinner, c);
        row++;

        // Max output tokens
        c.gridx = 0; c.gridy = row; c.fill = NONE; c.weightx = 0; c.anchor = WEST;
        panel.add(new JLabel("Max Output Tokens:"), c);
        c.gridx = 1; c.fill = NONE; c.weightx = 0;
        maxOutputTokensSpinner = new JSpinner(new SpinnerNumberModel(16384, 256, 200_000, 1024));
        maxOutputTokensSpinner.setToolTipText("Maximum tokens the model may generate per response. " +
                "Raise this for reasoning models (QwQ, DeepSeek-R1, etc.) whose thinking alone can exceed 4096.");
        panel.add(maxOutputTokensSpinner, c);
        row++;

        // Tool choice
        c.gridx = 0; c.gridy = row; c.fill = NONE; c.weightx = 0; c.anchor = WEST;
        panel.add(new JLabel("Tool Choice:"), c);
        c.gridx = 1; c.fill = NONE; c.weightx = 0;
        toolChoiceCombo = new JComboBox<>(new String[]{"auto", "required", "none"});
        toolChoiceCombo.setToolTipText(
                "auto: model decides when to call tools (default)\n" +
                "required: model must call a tool every turn — use when it plans but won't act\n" +
                "none: tools are sent but the model may not call them");
        panel.add(toolChoiceCombo, c);
        row++;

        // Parse text tool calls
        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.fill = HORIZONTAL; c.weightx = 1.0; c.anchor = WEST;
        parseTextToolCallsCheckbox = new JCheckBox("Parse tool calls in code fences (Python/JSON tags)");
        parseTextToolCallsCheckbox.setToolTipText(
                "<html>Enable for local models (e.g. Llama 3.3 via LM Studio) that output tool calls<br>" +
                "as JSON inside code fences rather than using the OpenAI tool_calls field.<br>" +
                "Only matches fences whose JSON 'name' matches a registered tool — safe against<br>" +
                "false positives on legitimate code examples.</html>");
        panel.add(parseTextToolCallsCheckbox, c);
        c.gridwidth = 1;
        row++;

        // Compression threshold
        c.gridx = 0; c.gridy = row; c.fill = NONE; c.weightx = 0; c.anchor = WEST;
        panel.add(new JLabel("Compression Threshold (tokens):"), c);
        c.gridx = 1; c.fill = NONE; c.weightx = 0;
        compressionThresholdSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 200_000, 1_000));
        compressionThresholdSpinner.setToolTipText(
                "<html>Automatically compress conversation history when prompt tokens exceed this value.<br>" +
                "0 = disabled. A good starting point is 60–70% of the model's context window.</html>");
        panel.add(compressionThresholdSpinner, c);
        row++;

        // System Prompt
        c.gridx = 0; c.gridy = row; c.fill = NONE; c.weightx = 0; c.anchor = NORTHWEST;
        panel.add(new JLabel("System Prompt:"), c);
        c.gridx = 1; c.fill = BOTH; c.weightx = 1.0; c.weighty = 1.0;
        systemPromptArea = new JTextArea(6, 32);
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        systemPromptArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(systemPromptArea), c);
        row++;

        // Test button + status
        c.gridx = 0; c.gridy = row; c.fill = NONE; c.weightx = 0; c.weighty = 0; c.anchor = WEST;
        testBtn = new LoadingButton("Test Connection");
        testBtn.addActionListener(e -> testConnection());
        panel.add(testBtn, c);

        c.gridx = 1; c.fill = HORIZONTAL; c.weightx = 1.0;
        statusLabel = new JLabel(" ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC));
        panel.add(statusLabel, c);

        return panel;
    }

    // -----------------------------------------------------------------------
    // Selection / editor loading
    // -----------------------------------------------------------------------

    /**
     * Fired when the list selection changes.
     *
     * <p>Order of operations:
     * <ol>
     *   <li>Flush current editor values → {@code editAgents[lastEditedIndex]}</li>
     *   <li>Refresh that row's label in the list</li>
     *   <li>Load the newly selected agent into the editor</li>
     *   <li>Update {@code lastEditedIndex}</li>
     * </ol>
     */
    private void onListSelectionChanged(ListSelectionEvent e) {
        if (e.getValueIsAdjusting() || suppressSelectionEvents) return;
        int newSel = agentList.getSelectedIndex();
        if (newSel < 0) return;

        // Flush the agent that was previously displayed in the editor
        if (lastEditedIndex >= 0 && lastEditedIndex < editAgents.size()) {
            flushEditorToAgent(lastEditedIndex);
        }

        loadAgentIntoEditor(newSel);
    }

    /** Writes the editor field values into {@code editAgents[idx]}. */
    private void flushEditorToAgent(int idx) {
        if (idx < 0 || idx >= editAgents.size()) return;
        AgentConfig a  = editAgents.get(idx);
        a.name               = nameField.getText().trim();
        a.apiEndpoint        = apiEndpointField.getText().trim();
        a.apiKey             = new String(apiKeyField.getPassword()).trim();
        a.model              = modelField.getText().trim();
        a.maxIterations      = (Integer) maxIterationsSpinner.getValue();
        a.maxOutputTokens    = (Integer) maxOutputTokensSpinner.getValue();
        a.toolChoice         = (String) toolChoiceCombo.getSelectedItem();
        a.parseTextToolCalls          = parseTextToolCallsCheckbox.isSelected();
        a.compressionThresholdTokens  = (Integer) compressionThresholdSpinner.getValue();
        a.systemPrompt                = systemPromptArea.getText();
        // Refresh the list row label (name or model may have changed)
        listModel.set(idx, a);
    }

    /** Populates editor fields from {@code editAgents[idx]}. */
    private void loadAgentIntoEditor(int idx) {
        if (idx < 0 || idx >= editAgents.size()) return;
        AgentConfig a = editAgents.get(idx);
        nameField.setText(a.name         != null ? a.name         : "");
        apiEndpointField.setText(a.apiEndpoint != null ? a.apiEndpoint : "");
        apiKeyField.setText(a.apiKey      != null ? a.apiKey      : "");
        modelField.setText(a.model != null ? a.model : "");
        maxIterationsSpinner.setValue(a.maxIterations > 0 ? a.maxIterations : 100);
        maxOutputTokensSpinner.setValue(a.maxOutputTokens > 0 ? a.maxOutputTokens : 16384);
        toolChoiceCombo.setSelectedItem(a.toolChoice != null ? a.toolChoice : "auto");
        parseTextToolCallsCheckbox.setSelected(a.parseTextToolCalls);
        compressionThresholdSpinner.setValue(a.compressionThresholdTokens);
        systemPromptArea.setText(a.systemPrompt != null ? a.systemPrompt : "");
        setStatus(" ", Color.GRAY);
        lastEditedIndex = idx;
    }

    // -----------------------------------------------------------------------
    // List mutation actions  (Add / Remove / Copy)
    // -----------------------------------------------------------------------

    private void addAgent() {
        // Flush the current editor before switching away
        if (lastEditedIndex >= 0) flushEditorToAgent(lastEditedIndex);

        AgentConfig fresh = new AgentConfig();
        fresh.name = "New Agent";
        int newIdx = editAgents.size();
        editAgents.add(fresh);

        suppressSelectionEvents = true;
        listModel.addElement(fresh);
        agentList.setSelectedIndex(newIdx);
        suppressSelectionEvents = false;

        loadAgentIntoEditor(newIdx);
        nameField.selectAll();
        nameField.requestFocusInWindow();
    }

    private void removeAgent() {
        if (editAgents.size() <= 1) {
            JOptionPane.showMessageDialog(agentList,
                    "At least one agent must exist.", "Cannot Remove",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        int sel = agentList.getSelectedIndex();
        if (sel < 0) return;

        // Suppress events for the entire mutation — we'll select + load manually
        suppressSelectionEvents = true;
        editAgents.remove(sel);
        listModel.remove(sel);      // may internally trigger selection changes
        lastEditedIndex = -1;       // removed agent no longer valid
        suppressSelectionEvents = false;

        int newSel = Math.min(sel, editAgents.size() - 1);
        suppressSelectionEvents = true;
        agentList.setSelectedIndex(newSel);
        suppressSelectionEvents = false;

        loadAgentIntoEditor(newSel);
    }

    private void copyAgent() {
        // Flush first so the copy captures the latest field values
        if (lastEditedIndex >= 0) flushEditorToAgent(lastEditedIndex);

        int sel = agentList.getSelectedIndex();
        if (sel < 0) return;
        AgentConfig copy = editAgents.get(sel).copy();
        copy.name = copy.name + " (copy)";
        int newIdx = editAgents.size();
        editAgents.add(copy);

        suppressSelectionEvents = true;
        listModel.addElement(copy);
        agentList.setSelectedIndex(newIdx);
        suppressSelectionEvents = false;

        loadAgentIntoEditor(newIdx);
    }

    // -----------------------------------------------------------------------
    // OK — validate, persist, notify
    // -----------------------------------------------------------------------

    @Override
    protected void doOKAction() {
        // Flush whatever is in the editor right now
        if (lastEditedIndex >= 0) flushEditorToAgent(lastEditedIndex);

        // Validate the currently visible agent
        if (lastEditedIndex >= 0 && lastEditedIndex < editAgents.size()) {
            AgentConfig a = editAgents.get(lastEditedIndex);
            if (a.apiEndpoint == null || a.apiEndpoint.isBlank()) {
                setStatus("❌ API Endpoint cannot be empty.", Color.RED);
                return;
            }
            try { new URL(a.apiEndpoint); }
            catch (MalformedURLException ex) {
                setStatus("❌ Invalid URL: " + ex.getMessage(), Color.RED);
                return;
            }
            if (a.model == null || a.model.isBlank()) {
                setStatus("❌ Model name cannot be empty.", Color.RED);
                return;
            }
        }

        persistToSettings();
        super.doOKAction();
    }

    private void persistToSettings() {
        CopilotSettings settings = CopilotSettings.getInstance();
        settings.agents = new ArrayList<>(editAgents);
        int sel = agentList.getSelectedIndex();
        settings.activeAgentIndex = (sel >= 0 && sel < editAgents.size()) ? sel : 0;
        settings.retainToolHistory    = retainToolHistoryCheckbox.isSelected();
        settings.showDynamicContextTab = showDynamicContextTabCheckbox.isSelected();

        ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(CopilotAgentsChangedListener.TOPIC)
                .agentsChanged();
    }

    // -----------------------------------------------------------------------
    // Test Connection — reads from fields directly, locks the form
    // -----------------------------------------------------------------------

    private void testConnection() {
        // Read values straight from the fields — do NOT flush to editAgents or
        // live settings; the user hasn't confirmed anything yet.
        String endpoint = apiEndpointField.getText().trim();
        String apiKey   = new String(apiKeyField.getPassword()).trim();
        String model    = modelField.getText().trim();

        if (endpoint.isBlank()) {
            setStatus("❌ API Endpoint is empty.", Color.RED); return;
        }
        try { new URL(endpoint); }
        catch (MalformedURLException ex) {
            setStatus("❌ Invalid URL: " + ex.getMessage(), Color.RED); return;
        }
        if (model.isBlank()) {
            setStatus("❌ Model name is empty.", Color.RED); return;
        }

        setStatus("⏳ Testing…", Color.GRAY);
        // Lock the non-button form elements; LoadingButton instances lock
        // themselves automatically via the CopilotLoadingStateNotifier bus.
        setNonButtonsEnabled(false);
        publishLoading(true);

        new Thread(() -> {
            String result = CopilotUtil.probeConnection(endpoint, apiKey, model);
            SwingUtilities.invokeLater(() -> {
                publishLoading(false);
                setNonButtonsEnabled(true);
                if (result == null) setStatus("✅ Connection successful!", new Color(0, 128, 0));
                else                setStatus("❌ " + result, Color.RED);
            });
        }, "copilot-connection-test").start();
    }

    /** Publishes a loading-state change on the application message bus. */
    private static void publishLoading(boolean loading) {
        CopilotLoadingStateNotifier pub = ApplicationManager.getApplication()
                .getMessageBus().syncPublisher(CopilotLoadingStateNotifier.TOPIC);
        if (loading) pub.loadingStarted();
        else         pub.loadingStopped();
    }

    /**
     * Enables/disables only the non-button form elements (fields, list).
     * All {@link LoadingButton} instances — including OK and Cancel — handle
     * their own state via the {@link CopilotLoadingStateNotifier} message bus.
     */
    private void setNonButtonsEnabled(boolean enabled) {
        nameField.setEnabled(enabled);
        apiEndpointField.setEnabled(enabled);
        apiKeyField.setEnabled(enabled);
        modelField.setEnabled(enabled);
        maxIterationsSpinner.setEnabled(enabled);
        maxOutputTokensSpinner.setEnabled(enabled);
        parseTextToolCallsCheckbox.setEnabled(enabled);
        compressionThresholdSpinner.setEnabled(enabled);
        retainToolHistoryCheckbox.setEnabled(enabled);
        showDynamicContextTabCheckbox.setEnabled(enabled);
        systemPromptArea.setEnabled(enabled);
        agentList.setEnabled(enabled);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void setStatus(String msg, Color color) {
        if (statusLabel == null) return;
        statusLabel.setText(msg);
        statusLabel.setForeground(color);
    }

    /** Renders each list row as bold name + smaller model string. */
    private static final class AgentCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof AgentConfig a) {
                String name  = esc(a.name);
                String model = (a.model != null && !a.model.isBlank()) ? esc(a.model) : "";
                setText("<html><b>" + name + "</b>"
                        + (model.isEmpty() ? "" : "<br><small><i>" + model + "</i></small>")
                        + "</html>");
            }
            return this;
        }
        private static String esc(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
        }
    }
}
