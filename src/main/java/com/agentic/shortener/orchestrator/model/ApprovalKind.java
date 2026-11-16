package com.agentic.shortener.orchestrator.model;

public enum ApprovalKind {
    /**
     * A human accepts the bounded interpretation of an ambiguous requirement.
     */
    ASSUMPTION_ACCEPTANCE(false),
    /**
     * Change control for a public API/schema change.
     */
    PUBLIC_API(true),
    PRODUCTION_DEPLOY(true);

    private final boolean fourEyes;

    ApprovalKind(boolean fourEyes) {
        this.fourEyes = fourEyes;
    }

    /**
     * Whether the approver must be someone other than the person who started the run.
     */
    public boolean requiresFourEyes() {
        return fourEyes;
    }
}
