package copilot;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
@State(
        name = "CopilotSettings",
        storages = @Storage("copilot_settings.xml")
)
public class CopilotSettings implements PersistentStateComponent<CopilotSettings> {
    public List<AgentConfig> agents = new ArrayList<>();
    public int activeAgentIndex = 0;
    public List<SavedSession> chatSessions = new ArrayList<>();
    public boolean retainToolHistory   = false;
    public boolean showDynamicContextTab = false;

    public static class SavedSession {
        public String name = "";
        public boolean titled = false;
        public List<SavedMessage> messages = new ArrayList<>();
        public String plan = null;
        public SavedSession() {}
    }

    public static class SavedMessage {
        public String role = "";
        public String content = "";
        public SavedMessage() {}
        public SavedMessage(String role, String content) { this.role = role; this.content = content; }
    }
    public static CopilotSettings getInstance() {
        return ApplicationManager.getApplication().getService(CopilotSettings.class);
    }
    @Nullable
    @Override
    public CopilotSettings getState() {
        return this;
    }
    @Override
    public void loadState(@NotNull CopilotSettings state) {
        XmlSerializerUtil.copyBean(state, this);
        ensureDefaults();
    }
    public AgentConfig getActiveAgent() {
        ensureDefaults();
        int idx = activeAgentIndex;
        if (idx < 0 || idx >= agents.size()) idx = 0;
        return agents.get(idx);
    }
    public void setActiveAgentIndex(int index) {
        if (index >= 0 && index < agents.size()) {
            activeAgentIndex = index;
        }
    }
    public String getApiEndpoint() { return getActiveAgent().apiEndpoint; }
    public String getApiKey()      { return getActiveAgent().apiKey; }
    public String getModel()       { return getActiveAgent().model; }
    public String getSystemPrompt(){ return getActiveAgent().systemPrompt; }
    public boolean isValidConfig() {
        return getActiveAgent().isValid();
    }
    private void ensureDefaults() {
        if (agents == null) agents = new ArrayList<>();
        if (agents.isEmpty()) {
            AgentConfig def = new AgentConfig();
            def.name = "Default";
            agents.add(def);
        }
        if (activeAgentIndex < 0 || activeAgentIndex >= agents.size()) {
            activeAgentIndex = 0;
        }
    }
}