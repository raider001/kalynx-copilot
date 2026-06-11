package copilot.memory;

import java.util.ArrayList;
import java.util.List;

/** Durable project fact stored in semantic memory. Must be a POJO for XML serialization. */
public class MemoryFact {
    public String id        = "";
    public String text      = "";
    public List<String> fileTags = new ArrayList<>();
    public String phase     = "";
    public long   timestamp = 0;
    public String source    = "";   // "model" | "plugin:transition" | "plugin:compression"

    public MemoryFact() {}

    public MemoryFact(String id, String text, List<String> fileTags, String phase, String source) {
        this.id        = id;
        this.text      = text;
        this.fileTags  = new ArrayList<>(fileTags);
        this.phase     = phase;
        this.timestamp = System.currentTimeMillis();
        this.source    = source;
    }
}
