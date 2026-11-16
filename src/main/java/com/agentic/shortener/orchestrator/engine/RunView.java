package com.agentic.shortener.orchestrator.engine;

import com.agentic.shortener.orchestrator.model.Approval;
import com.agentic.shortener.orchestrator.model.ArtifactInfo;
import com.agentic.shortener.orchestrator.model.DecisionRecord;
import com.agentic.shortener.orchestrator.model.FailureMode;
import com.agentic.shortener.orchestrator.model.RunState;
import com.agentic.shortener.orchestrator.model.Scenario;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of a run, safe to serialise while the run keeps executing.
 */
public record RunView(
        String id,
        Scenario scenario,
        RunState state,
        String error,
        String initiator,
        boolean autoApprove,
        FailureMode failureMode,
        Instant startedAt,
        Instant endedAt,
        List<StageView> stages,
        Map<String, Map<String, Object>> artifacts,
        Map<String, ArtifactInfo> artifactLineage,
        List<Approval> approvals,
        List<DecisionRecord> decisions,
        List<String> policyViolations,
        RunMetrics metrics) {

    public record RunMetrics(
            int retries,
            int fallbacks,
            int rollbacks,
            int replans,
            int approvalsRequested,
            long stagesSucceeded,
            long stagesFailed,
            Long endToEndLatencyMs) {
    }

    public StageView stage(String stageId) {
        return stages.stream().filter(s -> s.id().equals(stageId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no stage " + stageId));
    }
}
