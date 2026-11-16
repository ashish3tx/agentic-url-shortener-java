package com.agentic.shortener.orchestrator.model;

import java.time.Instant;
import java.util.Map;

/**
 * One entry in a run's decision lineage: what was decided, by whom, and why.
 */
public record DecisionRecord(
        String id,
        Instant at,
        String stageId,
        DecisionType type,
        String rationale,
        String outcome,
        String actor,
        Map<String, Object> inputs) {
}
