package com.agentic.shortener.orchestrator.error;

/**
 * A guardrail breach. Never retried: repeating the same action cannot make it compliant.
 */
public final class PolicyViolationException extends OrchestratorException {

    private final String rule;

    public PolicyViolationException(String rule, String message) {
        super(message);
        this.rule = rule;
    }

    public String rule() {
        return rule;
    }
}
