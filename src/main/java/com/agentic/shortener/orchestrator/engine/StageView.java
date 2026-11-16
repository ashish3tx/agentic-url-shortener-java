package com.agentic.shortener.orchestrator.engine;

import com.agentic.shortener.orchestrator.gate.Gate;
import com.agentic.shortener.orchestrator.model.ApprovalKind;
import com.agentic.shortener.orchestrator.model.Autonomy;
import com.agentic.shortener.orchestrator.model.JoinType;
import com.agentic.shortener.orchestrator.model.StageKind;
import com.agentic.shortener.orchestrator.model.StageState;

import java.time.Instant;
import java.util.List;

public record StageView(
        String id,
        String name,
        StageKind kind,
        String agent,
        List<String> dependsOn,
        JoinType join,
        String parallelGroup,
        Autonomy autonomy,
        ApprovalKind approvalKind,
        List<Gate> entryGates,
        List<Gate> exitGates,
        int maxAttempts,
        String fallbackAgent,
        String rollbackAgent,
        StageState state,
        int attempts,
        boolean usedFallback,
        Instant startedAt,
        Instant endedAt,
        String error) {
}
