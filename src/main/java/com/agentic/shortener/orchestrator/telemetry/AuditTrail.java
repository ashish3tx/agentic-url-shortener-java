package com.agentic.shortener.orchestrator.telemetry;

import java.util.List;

/** Durable, append-only store for orchestrator audit events. */
public interface AuditTrail {

    void append(TraceEvent event);

    /** Events for one run in the order they were recorded. */
    List<TraceEvent> forRun(String runId);
}
