package com.agentic.shortener.orchestrator.model;

import java.time.Instant;
import java.util.List;

public record Approval(
        String id,
        String stageId,
        ApprovalKind kind,
        ApprovalStatus status,
        Instant requestedAt,
        List<String> warnings,
        Instant resolvedAt,
        String actor,
        String comment) {

    public Approval {
        warnings = List.copyOf(warnings);
    }

    public static Approval pending(String id, String stageId, ApprovalKind kind, Instant at, List<String> warnings) {
        return new Approval(id, stageId, kind, ApprovalStatus.PENDING, at, warnings, null, null, null);
    }

    public Approval resolve(ApprovalStatus outcome, String byActor, String withComment, Instant at) {
        return new Approval(id, stageId, kind, outcome, requestedAt, warnings, at, byActor, withComment);
    }

    public boolean isPending() {
        return status == ApprovalStatus.PENDING;
    }
}
