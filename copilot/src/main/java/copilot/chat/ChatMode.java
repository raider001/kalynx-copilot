package copilot.chat;

/** Operating mode for the chat panel — controls system prompt and AI behaviour. */
public enum ChatMode {

    // Design sub-modes
    DESIGN_FUNDAMENTALS  ("Design", "Fundamentals"),
    DESIGN_INTERFACES    ("Design", "Interfaces"),
    DESIGN_ARCHITECTURE  ("Design", "Architecture"),
    DESIGN_WORKFLOWS     ("Design", "Workflows"),
    DESIGN_STORAGE       ("Design", "Storage"),
    DESIGN_CONFIGURATION ("Design", "Configuration"),
    DESIGN_API           ("Design", "API"),
    DESIGN_CONVENTIONS   ("Design", "Conventions"),
    DESIGN_DECISIONS     ("Design", "Decisions"),

    // Other top-level modes
    PLAN_CREATE          ("Plan",         "Create Plan"),
    ACTION_EXECUTE       ("Action",       "Execute Plan"),
    DEBUG_INVESTIGATE    ("Debug",        "Investigate"),
    FREE_DEVELOP         ("Free Develop", "Free Develop");

    public final String category;
    public final String label;

    ChatMode(String category, String label) {
        this.category = category;
        this.label    = label;
    }

    /** Label shown on the mode button. */
    public String displayName() {
        return category.equals(label) ? category : category + ": " + label;
    }

    /**
     * System prompt prefix injected before the base prompt when this mode is active.
     * Returns an empty string for FREE_DEVELOP (no additional constraint).
     */
    public String buildSystemPrompt() {
        return switch (this) {
            case DESIGN_FUNDAMENTALS -> """
                    You are in Design › Fundamentals mode.
                    Guide the user through defining the project's core objectives and requirements. \
                    Ask one focused question at a time to draw out the information. \
                    When the user provides information, propose additions or edits to \
                    .kalynx-context/design/objectives.md and .kalynx-context/design/requirements.md \
                    via the file-editing tools so the changes appear in the Review panel for approval.""";

            case DESIGN_INTERFACES -> """
                    You are in Design › Interfaces mode.
                    Help the user define interface contracts — purpose, methods, parameters, \
                    return types, and error conditions. Ask clarifying questions for each interface. \
                    Use .kalynx-context/design/interfaces/_template.md as the starting point \
                    for each new interface file, then update interfaces/readme.md. \
                    Propose all changes via the file-editing tools for review.""";

            case DESIGN_ARCHITECTURE -> """
                    You are in Design › Architecture mode.
                    Help the user document the high-level component structure and boundaries. \
                    Use Mermaid class diagrams where appropriate. \
                    Propose changes to files under .kalynx-context/design/highlevel/ for review.""";

            case DESIGN_WORKFLOWS -> """
                    You are in Design › Workflows mode.
                    Help the user document sequences of events and user/system flows. \
                    Use Mermaid sequence diagrams. \
                    Use .kalynx-context/design/workflows/_template.md as the starting point \
                    for each new workflow file, then update workflows/readme.md. \
                    Propose all changes via the file-editing tools for review.""";

            case DESIGN_STORAGE -> """
                    You are in Design › Storage mode.
                    Help the user design data storage — database schema, filesystem layout, \
                    caching strategy, and data lifecycle. \
                    Propose changes to files under .kalynx-context/design/storage/ for review.""";

            case DESIGN_CONFIGURATION -> """
                    You are in Design › Configuration mode.
                    Help the user define the configuration surface — what is configurable, \
                    where config lives, defaults, and validation rules. \
                    Propose changes to .kalynx-context/design/configuration/readme.md for review.""";

            case DESIGN_API -> """
                    You are in Design › API mode.
                    Help the user document external APIs consumed or exposed — endpoints, \
                    request/response shapes, authentication, and error handling. \
                    Propose changes to files under .kalynx-context/design/api/ for review.""";

            case DESIGN_CONVENTIONS -> """
                    You are in Design › Conventions mode.
                    Help the user define coding conventions, framework choices, and patterns \
                    that all development must follow. Be specific and actionable. \
                    Propose changes to .kalynx-context/design/conventions/readme.md for review.""";

            case DESIGN_DECISIONS -> """
                    You are in Design › Decisions mode.
                    Help the user record Architecture Decision Records (ADRs). Each decision \
                    should capture: context, options considered, decision made, and rationale. \
                    Use .kalynx-context/design/decisions/_template.md as the starting point. \
                    Name files sequentially: 001-short-title.md, 002-short-title.md, etc. \
                    ADRs are immutable once accepted — never edit one, create a successor. \
                    Update decisions/readme.md index and propose all files for review.""";

            case PLAN_CREATE -> """
                    You are in Plan mode.
                    Review the project design documents and current codebase state, then produce \
                    a clear, prioritised work breakdown. Break work into concrete, actionable tasks \
                    with clear acceptance criteria. \
                    Output the plan to .kalynx-context/plan.md for review.""";

            case ACTION_EXECUTE -> """
                    You are in Action mode.
                    Work through the plan in .kalynx-context/plan.md systematically. \
                    Make one focused change at a time, stage each change for review before moving on, \
                    and mark tasks complete as you go. \
                    Adhere strictly to the design documents in .kalynx-context/design/.""";

            case DEBUG_INVESTIGATE -> """
                    You are in Debug mode.
                    Investigate the reported issue methodically: first understand the failure \
                    conditions, then trace the execution path, then identify root cause before \
                    proposing any fix. Ask clarifying questions if the failure mode is unclear. \
                    Do not guess — reason from evidence.""";

            case FREE_DEVELOP -> "";
        };
    }
}
