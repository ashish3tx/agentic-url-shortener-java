package com.agentic.shortener.orchestrator.model;

public enum StageState {
    PENDING, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, ROLLED_BACK, SKIPPED, BLOCKED;

    public boolean isTerminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, ROLLED_BACK, SKIPPED, BLOCKED -> true;
            case PENDING, RUNNING, WAITING_APPROVAL -> false;
        };
    }

    public boolean isFailure() {
        return this == FAILED || this == ROLLED_BACK;
    }
}
