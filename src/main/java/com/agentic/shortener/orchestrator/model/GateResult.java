package com.agentic.shortener.orchestrator.model;

import com.agentic.shortener.orchestrator.gate.Gate;

public record GateResult(Gate gate, boolean passed, String reason) {
}
