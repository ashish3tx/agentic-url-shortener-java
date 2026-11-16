package com.agentic.shortener.orchestrator.model;

/**
 * The three delivery situations the assignment asks for; each maps to a different stage graph.
 */
public enum Scenario {

    GREENFIELD("Build a URL shortener",
            "Build a URL shortener service from scratch with core APIs, analytics, and reliability features.",
            true),
    BROWNFIELD("Enhance the existing shortener",
            "Add custom aliases, TTL expiration, PATCH updates and per-identity write-path rate limits "
                    + "to the existing service without breaking redirects.",
            true),
    AMBIGUOUS("Make it enterprise-ready",
            "Make the URL shortener enterprise-ready for a financial-services context.",
            false);

    private final String title;
    private final String brief;
    private final boolean wellDefined;

    Scenario(String title, String brief, boolean wellDefined) {
        this.title = title;
        this.brief = brief;
        this.wellDefined = wellDefined;
    }

    public String title() {
        return title;
    }

    public String brief() {
        return brief;
    }

    public boolean wellDefined() {
        return wellDefined;
    }
}
