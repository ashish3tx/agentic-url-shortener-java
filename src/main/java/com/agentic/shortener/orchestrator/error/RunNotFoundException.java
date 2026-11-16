package com.agentic.shortener.orchestrator.error;

public final class RunNotFoundException extends OrchestratorException {

    public RunNotFoundException(String runId) {
        super("run '" + runId + "' not found");
    }
}
