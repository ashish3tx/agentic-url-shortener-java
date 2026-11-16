package com.agentic.shortener.orchestrator.telemetry;

import java.time.Instant;

public record ReliabilityMetrics(
        long runsStarted,
        long runsSucceeded,
        long runsFailed,
        long runsStopped,
        double successRate,
        long retries,
        long fallbacks,
        long rollbacks,
        long replans,
        long approvalsRequested,
        long recoveries,
        double mttrMs,
        double avgEndToEndLatencyMs,
        Instant asOf) {
}
