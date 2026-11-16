package com.agentic.shortener.orchestrator.telemetry;

import java.time.Instant;
import java.util.Map;

public record TraceEvent(long sequence, Instant at, String runId, String stageId, String event,
                         Map<String, Object> detail) {
}
