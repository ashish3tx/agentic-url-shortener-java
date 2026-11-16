package com.agentic.shortener.orchestrator.engine;

import com.agentic.shortener.orchestrator.model.RunState;
import com.agentic.shortener.orchestrator.model.Scenario;

import java.time.Instant;

public record RunSummary(String id, Scenario scenario, RunState state, String initiator, Instant startedAt,
                         Instant endedAt) {
}
