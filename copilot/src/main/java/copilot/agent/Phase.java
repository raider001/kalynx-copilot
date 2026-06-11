package copilot.agent;

public enum Phase {
    ANALYSE, PLAN, IMPLEMENT, VERIFY, DONE;

    public String label() {
        String s = name();
        return s.charAt(0) + s.substring(1).toLowerCase();
    }
}
