package com.agentic.shortener.orchestrator.model;

/**
 * How far an agent may act on its own inside a stage.
 */
public enum Autonomy {
    /**
     * Deterministic, low-impact work; no human in the loop.
     */
    FULL,
    /**
     * Agent acts, output is reviewable via lineage; gates still apply.
     */
    SUPERVISED,
    /**
     * The stage cannot complete without an explicit human decision.
     */
    HUMAN_GATED
}
