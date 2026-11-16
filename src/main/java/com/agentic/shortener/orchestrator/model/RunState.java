package com.agentic.shortener.orchestrator.model;

public enum RunState {
    RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, STOPPED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == STOPPED;
    }
}
