package com.agentic.shortener.orchestrator.model;

/**
 * Fault injection for demos and tests of the reliability controls.
 */
public enum FailureMode {
    NONE,
    /**
     * Primary contract suite fails once, then passes: exercises bounded retry.
     */
    TRANSIENT,
    /**
     * Primary contract suite always fails: exercises fallback to the smoke suite.
     */
    PERSISTENT,
    /**
     * Primary contract suite hangs once: exercises the stage timeout, then retry.
     */
    HANG
}
