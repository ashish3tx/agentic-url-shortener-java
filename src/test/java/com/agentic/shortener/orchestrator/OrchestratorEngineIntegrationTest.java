package com.agentic.shortener.orchestrator;

import com.agentic.shortener.orchestrator.engine.OrchestratorEngine;
import com.agentic.shortener.orchestrator.engine.RunView;
import com.agentic.shortener.orchestrator.engine.StageView;
import com.agentic.shortener.orchestrator.error.IllegalRunStateException;
import com.agentic.shortener.orchestrator.error.PolicyViolationException;
import com.agentic.shortener.orchestrator.model.ApprovalStatus;
import com.agentic.shortener.orchestrator.model.DecisionType;
import com.agentic.shortener.orchestrator.model.FailureMode;
import com.agentic.shortener.orchestrator.model.RunState;
import com.agentic.shortener.orchestrator.model.Scenario;
import com.agentic.shortener.orchestrator.model.StageState;
import com.agentic.shortener.orchestrator.telemetry.TraceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrchestratorEngineIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String REQUESTER = "alice";
    private static final String REVIEWER = "bob";

    @Autowired
    private OrchestratorEngine engine;

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void everyScenarioCompletesEndToEndUnderAutoApprove(Scenario scenario) throws Exception {
        var run = settle(engine.start(scenario, true, FailureMode.NONE, REQUESTER));

        assertThat(run.state()).isEqualTo(RunState.SUCCEEDED);
        assertThat(run.stages()).allSatisfy(stage -> assertThat(stage.state()).isEqualTo(StageState.SUCCEEDED));
        assertThat(run.artifacts().get("tests")).containsEntry("failed", 0);
        assertThat(run.artifacts().get("release")).containsEntry("decision", "GO_PENDING_HUMAN_APPROVAL");
        assertThat(run.decisions()).anyMatch(d -> d.type() == DecisionType.APPROVAL && "auto-approve".equals(d.actor()));
        assertThat(run.metrics().endToEndLatencyMs()).isNotNull();
    }

    @Test
    void joinStageWaitsForEveryParallelStream() throws Exception {
        var run = settle(engine.start(Scenario.GREENFIELD, true, FailureMode.NONE, REQUESTER));

        var sync = run.stage("sync_impl");
        for (var stream : List.of("impl_api", "impl_store", "impl_analytics")) {
            assertThat(sync.startedAt()).isAfterOrEqualTo(run.stage(stream).endedAt());
        }
        var release = run.stage("release");
        assertThat(release.startedAt()).isAfterOrEqualTo(run.stage("testing").endedAt())
                .isAfterOrEqualTo(run.stage("documentation").endedAt());
    }

    @Test
    void releaseWaitsForHumanAndEnforcesFourEyes() throws Exception {
        var run = settle(engine.start(Scenario.GREENFIELD, false, FailureMode.NONE, REQUESTER));

        assertThat(run.state()).isEqualTo(RunState.WAITING_APPROVAL);
        assertThat(run.stage("release").state()).isEqualTo(StageState.WAITING_APPROVAL);
        assertThatThrownBy(() -> engine.approve(run.id(), "release", REQUESTER, "self-approve"))
                .isInstanceOf(PolicyViolationException.class)
                .hasMessageContaining("someone other than the run initiator");
        assertThatThrownBy(() -> engine.approve(run.id(), null, "auto-approve", "impersonation"))
                .isInstanceOf(PolicyViolationException.class);

        var approved = settle(engine.approve(run.id(), "release", REVIEWER, "change ticket CHG-42"));

        assertThat(approved.state()).isEqualTo(RunState.SUCCEEDED);
        assertThat(approved.approvals()).singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(ApprovalStatus.APPROVED))
                .satisfies(a -> assertThat(a.actor()).isEqualTo(REVIEWER));
        assertThat(approved.decisions()).anyMatch(d -> d.type() == DecisionType.POLICY && d.outcome().equals("blocked"));
    }

    @Test
    void ambiguousRequestStopsForAssumptionAcceptanceBeforeDesign() throws Exception {
        var run = settle(engine.start(Scenario.AMBIGUOUS, false, FailureMode.NONE, REQUESTER));

        assertThat(run.state()).isEqualTo(RunState.WAITING_APPROVAL);
        assertThat(run.stage("normalize").state()).isEqualTo(StageState.WAITING_APPROVAL);
        assertThat(run.stage("architecture").state()).isEqualTo(StageState.PENDING);
        assertThat((List<?>) run.artifacts().get("requirements").get("openQuestions")).isNotEmpty();

        var atRelease = settle(engine.approve(run.id(), "normalize", REQUESTER, "assumptions accepted"));
        assertThat(atRelease.stage("release").state()).isEqualTo(StageState.WAITING_APPROVAL);
        assertThat(atRelease.artifacts().get("architecture").get("adrs").toString()).contains("ADR-3");

        assertThat(settle(engine.approve(run.id(), "release", REVIEWER, "go")).state()).isEqualTo(RunState.SUCCEEDED);
    }

    @Test
    void rejectionRollsBackRelease() throws Exception {
        var run = settle(engine.start(Scenario.BROWNFIELD, false, FailureMode.NONE, REQUESTER));

        var rejected = settle(engine.reject(run.id(), "release", REVIEWER, "breaking change spotted"));

        assertThat(rejected.state()).isEqualTo(RunState.FAILED);
        assertThat(rejected.stage("release").state()).isEqualTo(StageState.ROLLED_BACK);
        assertThat(rejected.artifacts().get("release")).containsEntry("decision", "NO_GO").containsEntry("rolledBack", true);
        assertThat(rejected.metrics().rollbacks()).isEqualTo(1);
        assertThat(rejected.error()).contains("breaking change spotted");
    }

    @Test
    void transientTestFailureIsRetriedAndRecorded() throws Exception {
        var run = settle(engine.start(Scenario.GREENFIELD, true, FailureMode.TRANSIENT, REQUESTER));

        assertThat(run.state()).isEqualTo(RunState.SUCCEEDED);
        StageView testing = run.stage("testing");
        assertThat(testing.attempts()).isEqualTo(2);
        assertThat(testing.usedFallback()).isFalse();
        assertThat(run.metrics().retries()).isEqualTo(1);
        assertThat(run.decisions()).anyMatch(d -> d.type() == DecisionType.RETRY && d.outcome().equals("retrying"));
    }

    @Test
    void hungAgentIsCancelledByStageTimeoutThenRetried() throws Exception {
        var run = engine.awaitSettled(engine.start(Scenario.GREENFIELD, true, FailureMode.HANG, REQUESTER).id(), Duration.ofSeconds(30));

        assertThat(run.state()).isEqualTo(RunState.SUCCEEDED);
        assertThat(run.stage("testing").attempts()).isEqualTo(2);
        assertThat(run.decisions()).anyMatch(d -> d.type() == DecisionType.RETRY && d.rationale().contains("timed out"));
    }

    @Test
    void decisionsArePersistedToTheDurableAuditTrail() throws Exception {
        var run = settle(engine.start(Scenario.GREENFIELD, true, FailureMode.NONE, REQUESTER));

        assertThat(engine.trace(run.id()))
                .filteredOn(e -> e.event().equals("decision"))
                .hasSize(run.decisions().size());
    }

    @Test
    void persistentFailureFallsBackAndWarnsTheApprover() throws Exception {
        var run = settle(engine.start(Scenario.GREENFIELD, false, FailureMode.PERSISTENT, REQUESTER));

        assertThat(run.state()).isEqualTo(RunState.WAITING_APPROVAL);
        assertThat(run.stage("testing").usedFallback()).isTrue();
        assertThat(run.stage("testing").attempts()).isEqualTo(3);
        assertThat(run.artifacts().get("tests")).containsEntry("degradedCoverage", true).containsEntry("suite", "smoke");
        assertThat(run.approvals()).singleElement().satisfies(a -> assertThat(a.warnings()).isNotEmpty());
        assertThat(run.metrics().fallbacks()).isEqualTo(1);
    }

    @Test
    void amendingRequirementsReplansDownstreamAndSupersedesApproval() throws Exception {
        var run = settle(engine.start(Scenario.GREENFIELD, false, FailureMode.NONE, REQUESTER));
        @SuppressWarnings("unchecked")
        var criteria = new ArrayList<>((List<String>) run.artifacts().get("requirements").get("acceptanceCriteria"));
        criteria.add("Links can be exported as CSV");

        engine.amend(run.id(), "requirements", Map.of("acceptanceCriteria", criteria), REQUESTER, "PO added export");
        var replanned = settle(run.id());

        assertThat(replanned.state()).isEqualTo(RunState.WAITING_APPROVAL);
        assertThat(replanned.metrics().replans()).isEqualTo(1);
        assertThat(replanned.artifactLineage().get("requirements").version()).isEqualTo(2);
        assertThat(replanned.artifacts().get("documentation").get("acceptanceCriteria").toString()).contains("CSV");
        assertThat(replanned.stage("architecture").attempts()).isEqualTo(1);
        assertThat(replanned.approvals()).extracting(a -> a.status())
                .containsExactly(ApprovalStatus.SUPERSEDED, ApprovalStatus.PENDING);
        assertThat(replanned.decisions()).anyMatch(d -> d.type() == DecisionType.REPLAN);

        assertThat(settle(engine.approve(run.id(), "release", REVIEWER, "ok")).state()).isEqualTo(RunState.SUCCEEDED);
    }

    @Test
    void replanAfterAssumptionAcceptanceRequiresAcceptanceAgain() throws Exception {
        var run = settle(engine.start(Scenario.AMBIGUOUS, false, FailureMode.NONE, REQUESTER));
        settle(engine.approve(run.id(), "normalize", REQUESTER, "accepted"));

        engine.amend(run.id(), "requirements", Map.of("assumptions", List.of("SSO via OIDC is required")), REQUESTER, "CISO input");
        var replanned = settle(run.id());

        assertThat(replanned.stage("normalize").state()).isEqualTo(StageState.WAITING_APPROVAL);
        assertThat(replanned.stage("architecture").state()).isEqualTo(StageState.PENDING);
        assertThat(replanned.artifacts()).doesNotContainKey("architecture");
    }

    @Test
    void amendmentContainingASecretIsBlocked() throws Exception {
        var run = settle(engine.start(Scenario.GREENFIELD, false, FailureMode.NONE, REQUESTER));

        assertThatThrownBy(() -> engine.amend(run.id(), "requirements", Map.of("notes", "db password=hunter22"), REQUESTER, "oops"))
                .isInstanceOf(PolicyViolationException.class);

        var after = engine.get(run.id());
        assertThat(after.artifactLineage().get("requirements").version()).isEqualTo(1);
        assertThat(after.state()).isEqualTo(RunState.WAITING_APPROVAL);
    }

    @Test
    void safeStopSkipsRemainingWorkAndRefusesFurtherActions() throws Exception {
        var run = settle(engine.start(Scenario.GREENFIELD, false, FailureMode.NONE, REQUESTER));

        var stopped = settle(engine.stop(run.id(), REVIEWER));

        assertThat(stopped.state()).isEqualTo(RunState.STOPPED);
        assertThat(stopped.stage("release").state()).isEqualTo(StageState.SKIPPED);
        assertThat(stopped.approvals()).allSatisfy(a -> assertThat(a.status()).isEqualTo(ApprovalStatus.SUPERSEDED));
        assertThatThrownBy(() -> engine.approve(run.id(), "release", REVIEWER, "too late"))
                .isInstanceOf(IllegalRunStateException.class);
    }

    @Test
    void traceIsOrderedAuditTrailAndMetricsAggregate() throws Exception {
        var run = settle(engine.start(Scenario.BROWNFIELD, true, FailureMode.NONE, REQUESTER));
        var trace = engine.trace(run.id());

        assertThat(trace).extracting(TraceEvent::event).startsWith("run_started").endsWith("run_finished")
                .contains("stage_started", "artifact_published", "approval_requested", "approved");
        assertThat(trace).extracting(TraceEvent::sequence).isSorted();
        assertThat(run.artifacts().get("impact").get("liveEndpoints").toString()).contains("ShortUrlController#update");

        var metrics = engine.metrics();
        assertThat(metrics.runsSucceeded()).isPositive();
        assertThat(metrics.successRate()).isBetween(0.0, 1.0);
        assertThat(metrics.avgEndToEndLatencyMs()).isPositive();
    }

    private RunView settle(RunView view) throws InterruptedException {
        return settle(view.id());
    }

    private RunView settle(String runId) throws InterruptedException {
        var run = engine.awaitSettled(runId, TIMEOUT);
        assertThat(run.state()).as("run did not settle: %s", run.error()).isIn(
                RunState.WAITING_APPROVAL, RunState.SUCCEEDED, RunState.FAILED, RunState.STOPPED);
        return run;
    }
}
