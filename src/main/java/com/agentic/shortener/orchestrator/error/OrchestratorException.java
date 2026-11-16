package com.agentic.shortener.orchestrator.error;

public sealed abstract class OrchestratorException extends RuntimeException
        permits RunNotFoundException, IllegalRunStateException, PolicyViolationException {

    protected OrchestratorException(String message) {
        super(message);
    }
}
