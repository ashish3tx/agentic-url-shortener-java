package com.agentic.shortener.orchestrator.engine;

import com.agentic.shortener.config.OrchestratorProperties;
import com.agentic.shortener.orchestrator.agent.AgentRegistry;
import com.agentic.shortener.orchestrator.error.IllegalRunStateException;
import com.agentic.shortener.orchestrator.error.PolicyViolationException;
import com.agentic.shortener.orchestrator.error.RunNotFoundException;
import com.agentic.shortener.orchestrator.gate.Gate;
import com.agentic.shortener.orchestrator.graph.ScenarioCatalog;
import com.agentic.shortener.orchestrator.model.Approval;
import com.agentic.shortener.orchestrator.model.ApprovalKind;
import com.agentic.shortener.orchestrator.model.ApprovalStatus;
import com.agentic.shortener.orchestrator.model.ArtifactInfo;
import com.agentic.shortener.orchestrator.model.DecisionRecord;
import com.agentic.shortener.orchestrator.model.DecisionType;
import com.agentic.shortener.orchestrator.model.FailureMode;
import com.agentic.shortener.orchestrator.model.JoinType;
import com.agentic.shortener.orchestrator.model.RunContext;
import com.agentic.shortener.orchestrator.model.RunState;
import com.agentic.shortener.orchestrator.model.Scenario;
import com.agentic.shortener.orchestrator.model.StageContext;
import com.agentic.shortener.orchestrator.model.StageSpec;
import com.agentic.shortener.orchestrator.model.StageState;
import com.agentic.shortener.orchestrator.policy.PolicyEngine;
import com.agentic.shortener.orchestrator.telemetry.OrchestratorTelemetry;
import com.agentic.shortener.orchestrator.telemetry.ReliabilityMetrics;
import com.agentic.shortener.orchestrator.telemetry.TraceEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.agentic.shortener.orchestrator.policy.PolicyEngine.AUTO_APPROVER;

/**
 * Stateful DAG executor for the SDLC lifecycle.
 *
 * <p>One scheduler loop per run launches every stage whose dependencies are satisfied, so
 * independent stages run in parallel and join stages wait for all (or any) upstream. Each stage
 * passes entry gates, runs its agent, passes exit gates and, if high impact, waits for a human.
 * Failures go through bounded retry, then fallback, then rollback. A run can be safely stopped,
 * and amending an upstream artifact re-plans the affected downstream stages.
 */
@Service
public class OrchestratorEngine {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorEngine.class);
    private static final long SCHEDULER_TICK_MS = 250;
    private static final String SYSTEM = "orchestrator";

    private final ScenarioCatalog catalog;
    private final AgentRegistry agents;
    private final PolicyEngine policy;
    private final OrchestratorTelemetry telemetry;
    private final Clock clock;
    private final OrchestratorProperties properties;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final ExecutorService executor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("sdlc-", 0).factory());

    public OrchestratorEngine(ScenarioCatalog catalog, AgentRegistry agents, PolicyEngine policy,
                              OrchestratorTelemetry telemetry, Clock clock, OrchestratorProperties properties) {
        this.catalog = catalog;
        this.agents = agents;
        this.policy = policy;
        this.telemetry = telemetry;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Graceful shutdown: safe-stop every active run so in-flight stages drain and the audit trail
     * records why, then interrupt whatever is still running after the grace period.
     */
    @PreDestroy
    void shutdown() {
        var active = runs.values().stream().filter(run -> run.locked(() -> !run.state.isTerminal())).toList();
        for (var run : active) {
            try {
                stop(run.id, "system:shutdown");
            } catch (IllegalRunStateException alreadyWindingDown) {
                // nothing to do
            }
        }
        long deadline = System.nanoTime() + properties.shutdownGracePeriod().toNanos();
        for (var run : active) {
            try {
                awaitSettled(run.id, Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        executor.shutdownNow();
    }

    /** Keeps memory bounded: finished runs leave the in-memory map; their audit trail stays in the database. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void evictFinishedRuns() {
        var cutoff = clock.instant().minus(properties.runRetention());
        var finished = runs.values().stream()
                .filter(run -> run.locked(() -> run.state.isTerminal()))
                .sorted(Comparator.comparing((Run run) -> run.endedAt))
                .toList();
        int overCapacity = Math.max(0, runs.size() - properties.maxRetainedRuns());
        for (var run : finished) {
            if (run.endedAt.isBefore(cutoff) || overCapacity > 0) {
                runs.remove(run.id);
                overCapacity--;
            }
        }
    }

    // ---------------------------------------------------------------- public API

    public RunView start(Scenario scenario, boolean autoApprove, FailureMode failureMode, String initiator) {
        Objects.requireNonNull(scenario, "scenario");
        policy.checkRunStart(autoApprove, initiator);
        var mode = failureMode == null ? FailureMode.NONE : failureMode;
        var id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var run = new Run(id, scenario, catalog.graph(scenario), initiator, autoApprove,
                new RunContext(id, scenario, mode), clock.instant());
        runs.put(id, run);

        telemetry.runStarted();
        telemetry.emit(id, null, "run_started", Map.of(
                "scenario", scenario.name(), "initiator", initiator, "autoApprove", autoApprove, "failureMode", mode.name()));
        var humanGates = run.graph.stages().stream().filter(StageSpec::requiresApproval).map(StageSpec::id).toList();
        decide(run, "run", DecisionType.AUTONOMY,
                "run started under controlled autonomy; human checkpoints at " + humanGates,
                "running", initiator, Map.of("scenario", scenario.name(), "autoApprove", autoApprove));
        executor.execute(() -> drive(run));
        return view(run);
    }

    public RunView get(String runId) {
        return view(require(runId));
    }

    public List<RunSummary> list() {
        return runs.values().stream()
                .map(run -> run.locked(() -> new RunSummary(run.id, run.scenario, run.state, run.initiator, run.startedAt, run.endedAt)))
                .sorted(Comparator.comparing(RunSummary::startedAt).reversed())
                .toList();
    }

    /** Read from the durable audit trail, so it still works after the run was evicted or the app restarted. */
    public List<TraceEvent> trace(String runId) {
        var events = telemetry.trace(runId);
        if (events.isEmpty() && !runs.containsKey(runId)) {
            throw new RunNotFoundException(runId);
        }
        return events;
    }

    public ReliabilityMetrics metrics() {
        return telemetry.snapshot();
    }

    public RunView approve(String runId, String stageId, String actor, String comment) {
        var run = require(runId);
        return run.locked(() -> {
            ensureActive(run);
            var approval = pendingApproval(run, stageId);
            try {
                policy.checkApprover(approval, actor, run.initiator, run.autoApprove);
            } catch (PolicyViolationException e) {
                decide(run, approval.stageId(), DecisionType.POLICY, e.getMessage(), "blocked", actor, Map.of("rule", e.rule()));
                throw e;
            }
            var now = clock.instant();
            replaceApproval(run, approval.resolve(ApprovalStatus.APPROVED, actor, comment, now));
            run.stages.get(approval.stageId()).succeed(now);
            if (approval.kind() == ApprovalKind.ASSUMPTION_ACCEPTANCE) {
                run.ctx.acceptAssumptions();
            }
            decide(run, approval.stageId(), DecisionType.APPROVAL,
                    actor + " approved " + approval.kind() + ": " + Objects.toString(comment, ""),
                    "approved", actor, Map.of("approvalId", approval.id()));
            telemetry.emit(run.id, approval.stageId(), "approved", Map.of("actor", actor, "kind", approval.kind().name()));
            resume(run);
            return view(run);
        });
    }

    public RunView reject(String runId, String stageId, String actor, String comment) {
        policy.requireActor(actor);
        var run = require(runId);
        return run.locked(() -> {
            ensureActive(run);
            var approval = pendingApproval(run, stageId);
            var now = clock.instant();
            replaceApproval(run, approval.resolve(ApprovalStatus.REJECTED, actor, comment, now));
            var stage = run.stages.get(approval.stageId());
            var reason = "rejected by " + actor + ": " + Objects.toString(comment, "");
            stage.fail(now, reason);
            decide(run, stage.spec().id(), DecisionType.APPROVAL, reason, "rejected", actor, Map.of("approvalId", approval.id()));
            telemetry.emit(run.id, stage.spec().id(), "rejected", Map.of("actor", actor));
            rollback(run, stage);
            run.markFailure(stage.spec().id() + ": " + reason);
            resume(run);
            return view(run);
        });
    }

    public RunView stop(String runId, String actor) {
        policy.requireActor(actor);
        var run = require(runId);
        return run.locked(() -> {
            ensureActive(run);
            run.ctx.requestStop();
            decide(run, "run", DecisionType.SAFE_STOP,
                    "safe-stop requested by " + actor + "; in-flight stages drain, nothing new starts",
                    "stopping", actor, Map.of());
            telemetry.emit(run.id, null, "safe_stop_requested", Map.of("actor", actor));
            resume(run);
            return view(run);
        });
    }

    /**
     * Human amends an upstream artifact (e.g. adds an acceptance criterion). Everything downstream
     * of the producing stage is invalidated and re-executes against the new context; approvals
     * already given for that work are superseded, so governance is never bypassed by a re-plan.
     */
    public RunView amend(String runId, String artifactName, Map<String, Object> changes, String actor, String reason) {
        policy.requireActor(actor);
        var run = require(runId);
        return run.locked(() -> {
            ensureActive(run);
            var current = run.ctx.artifact(artifactName)
                    .orElseThrow(() -> new IllegalRunStateException("artifact '" + artifactName + "' does not exist yet"));
            var producer = run.ctx.lineage(artifactName).map(ArtifactInfo::producedBy).orElseThrow();
            var downstream = run.graph.descendantsOf(producer);
            var busy = new ArrayList<String>();
            if (run.inFlight.contains(producer)) {
                busy.add(producer);
            }
            downstream.stream().filter(run.inFlight::contains).forEach(busy::add);
            if (!busy.isEmpty()) {
                throw new IllegalRunStateException("cannot amend while " + busy + " are in flight; retry once they settle");
            }

            var amended = new LinkedHashMap<>(current);
            amended.putAll(changes);
            try {
                publish(run, producer, artifactName, amended);
            } catch (PolicyViolationException e) {
                decide(run, producer, DecisionType.POLICY, "amendment rejected: " + e.getMessage(), "blocked", actor, Map.of("rule", e.rule()));
                throw e;
            }

            var invalidated = invalidate(run, downstream);
            var producerStage = run.stages.get(producer);
            boolean reapprove = producerStage.spec().requiresApproval()
                    && (producerStage.state() == StageState.SUCCEEDED || producerStage.state() == StageState.WAITING_APPROVAL);
            if (reapprove) {
                // The human approved the old output; a changed output needs a fresh decision.
                supersedeApprovals(run, producer, "output amended");
                if (producerStage.spec().approvalKind() == ApprovalKind.ASSUMPTION_ACCEPTANCE) {
                    run.ctx.revokeAssumptions();
                }
                requestApproval(run, producerStage);
            }
            run.replans++;
            telemetry.replan();
            var sha = run.ctx.lineage(artifactName).map(ArtifactInfo::sha256).orElse("");
            decide(run, producer, DecisionType.REPLAN,
                    "'%s' amended by %s (%s); invalidated %s%s".formatted(artifactName, actor, reason, invalidated,
                            reapprove ? "; re-approval requested for " + producer : ""),
                    "replanned", actor, Map.of("artifact", artifactName, "sha256", sha, "invalidated", invalidated));
            telemetry.emit(run.id, producer, "replanned", Map.of("artifact", artifactName, "invalidated", invalidated, "actor", actor));
            resume(run);
            if (reapprove && run.autoApprove) {
                approve(run.id, producer, AUTO_APPROVER, "demo auto-approve after amendment");
            }
            return view(run);
        });
    }

    /**
     * Blocks until the run finishes or is parked on a human checkpoint. Used by tests and the CLI demo.
     */
    public RunView awaitSettled(String runId, Duration timeout) throws InterruptedException {
        var run = require(runId);
        long deadline = System.nanoTime() + timeout.toNanos();
        run.lock.lock();
        try {
            while (!run.isSettled()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                run.changed.awaitNanos(remaining);
            }
            return view(run);
        } finally {
            run.lock.unlock();
        }
    }

    // ---------------------------------------------------------------- scheduler

    private void drive(Run run) {
        run.lock.lock();
        try {
            while (!run.state.isTerminal()) {
                step(run);
                if (!run.state.isTerminal()) {
                    run.changed.await(SCHEDULER_TICK_MS, TimeUnit.MILLISECONDS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.markFailure("orchestrator interrupted");
            conclude(run);
        } catch (RuntimeException e) {
            log.error("run {} scheduler failed", run.id, e);
            run.markFailure("orchestrator error: " + e.getMessage());
            conclude(run);
        } finally {
            run.lock.unlock();
        }
    }

    /**
     * One scheduling pass. Caller holds the run lock.
     */
    private void step(Run run) {
        boolean halting = run.failure != null || run.ctx.stopRequested();
        if (halting) {
            // Drain: never interrupt in-flight agents, just stop launching new work.
            if (run.inFlight.isEmpty()) {
                conclude(run);
            }
            return;
        }
        launchReadyStages(run);
        if (!run.inFlight.isEmpty()) {
            transition(run, RunState.RUNNING);
        } else if (run.hasPendingApproval()) {
            transition(run, RunState.WAITING_APPROVAL);
        } else if (run.allStagesTerminal()) {
            finish(run, RunState.SUCCEEDED);
        } else {
            var stuck = run.stages.values().stream().filter(s -> !s.state().isTerminal()).map(s -> s.spec().id()).toList();
            run.markFailure("graph deadlock: " + stuck + " can never become ready");
            conclude(run);
        }
    }

    private void launchReadyStages(Run run) {
        for (var stage : run.stages.values()) {
            if (stage.state() != StageState.PENDING || !dependenciesSatisfied(run, stage.spec())) {
                continue;
            }
            stage.start(clock.instant());
            run.inFlight.add(stage.spec().id());
            telemetry.emit(run.id, stage.spec().id(), "stage_started",
                    Map.of("parallelGroup", Objects.toString(stage.spec().parallelGroup(), "none")));
            executor.execute(() -> executeStage(run, stage));
        }
    }

    private boolean dependenciesSatisfied(Run run, StageSpec spec) {
        var deps = spec.dependsOn().stream().map(run.stages::get).toList();
        if (deps.isEmpty()) {
            return true;
        }
        return spec.join() == JoinType.ANY
                ? deps.stream().anyMatch(d -> d.state() == StageState.SUCCEEDED)
                : deps.stream().allMatch(d -> d.state() == StageState.SUCCEEDED);
    }

    // ---------------------------------------------------------------- stage execution (worker threads)

    private void executeStage(Run run, StageRuntime stage) {
        var spec = stage.spec();
        try {
            policy.checkStageStart(spec);
            if (runWithRetries(run, stage) || runFallback(run, stage)) {
                completeStage(run, stage);
            } else {
                failStage(run, stage, run.locked(stage::error));
            }
        } catch (PolicyViolationException e) {
            run.ctx.recordPolicyViolation(e.rule() + ": " + e.getMessage());
            decide(run, spec.id(), DecisionType.POLICY, e.getMessage(), "blocked", SYSTEM, Map.of("rule", e.rule()));
            failStage(run, stage, "policy violation: " + e.getMessage());
        } catch (StageCancelledException e) {
            run.locked(() -> stage.skip(clock.instant(), e.getMessage()));
            telemetry.emit(run.id, spec.id(), "stage_cancelled", Map.of("reason", e.getMessage()));
        } catch (RuntimeException e) {
            log.error("run {} stage {} crashed", run.id, spec.id(), e);
            failStage(run, stage, "unexpected error: " + e.getMessage());
        } finally {
            run.locked(() -> {
                run.inFlight.remove(spec.id());
                run.signal();
            });
        }
    }

    private boolean runWithRetries(Run run, StageRuntime stage) {
        var spec = stage.spec();
        for (int attempt = 1; attempt <= spec.maxAttempts(); attempt++) {
            ensureNotHalted(run);
            run.locked(stage::recordAttempt);
            try {
                attempt(run, stage, spec.agent());
                return true;
            } catch (PolicyViolationException | StageCancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                boolean willRetry = attempt < spec.maxAttempts();
                run.locked(() -> {
                    stage.recordFailure(clock.instant(), e.getMessage());
                    if (willRetry) {
                        run.retries++;
                    }
                });
                decide(run, spec.id(), DecisionType.RETRY,
                        "attempt %d/%d failed: %s".formatted(attempt, spec.maxAttempts(), e.getMessage()),
                        willRetry ? "retrying" : "exhausted", spec.agent(), Map.of("attempt", attempt));
                telemetry.emit(run.id, spec.id(), "attempt_failed", Map.of("attempt", attempt, "error", String.valueOf(e.getMessage())));
                if (willRetry) {
                    telemetry.retry();
                    backoff(spec.backoff().multipliedBy(1L << (attempt - 1)));
                }
            }
        }
        return false;
    }

    private boolean runFallback(Run run, StageRuntime stage) {
        var spec = stage.spec();
        if (spec.fallbackAgent() == null) {
            return false;
        }
        ensureNotHalted(run);
        run.locked(() -> {
            stage.markFallback();
            run.fallbacks++;
        });
        telemetry.fallback();
        decide(run, spec.id(), DecisionType.FALLBACK,
                "primary agent exhausted " + spec.maxAttempts() + " attempts; invoking fallback " + spec.fallbackAgent(),
                "fallback", spec.fallbackAgent(), Map.of());
        try {
            attempt(run, stage, spec.fallbackAgent());
            return true;
        } catch (PolicyViolationException | StageCancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            run.locked(() -> stage.recordFailure(clock.instant(), "fallback failed: " + e.getMessage()));
            decide(run, spec.id(), DecisionType.FALLBACK, "fallback failed: " + e.getMessage(), "exhausted", spec.fallbackAgent(), Map.of());
            return false;
        }
    }

    /**
     * Entry gates → agent → exit gates. The fallback goes through the same gates as the primary.
     */
    private void attempt(Run run, StageRuntime stage, String agentId) {
        var spec = stage.spec();
        checkGates(run, spec, spec.entryGates(), "entry");
        invokeWithTimeout(run, spec, agentId);
        checkGates(run, spec, spec.exitGates(), "exit");
    }

    /** A hung agent must not hold a stage forever: it is cancelled and counted as a failed attempt. */
    private void invokeWithTimeout(Run run, StageSpec spec, String agentId) {
        var agent = agents.get(agentId);
        var invocation = executor.submit(() -> agent.execute(stageContext(run, spec.id())));
        try {
            invocation.get(spec.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            invocation.cancel(true);
            throw new IllegalStateException("agent " + agentId + " timed out after " + spec.timeout());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("agent " + agentId + " failed: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            invocation.cancel(true);
            throw new StageCancelledException("interrupted while waiting for agent " + agentId);
        }
    }

    private void checkGates(Run run, StageSpec spec, List<Gate> gates, String phase) {
        for (var gate : gates) {
            var result = gate.evaluate(run.ctx);
            decide(run, spec.id(), DecisionType.GATE, phase + " gate " + gate + ": " + result.reason(),
                    result.passed() ? "passed" : "failed", SYSTEM, Map.of("gate", gate.name(), "phase", phase));
            if (!result.passed()) {
                throw new IllegalStateException(phase + " gate " + gate + " failed: " + result.reason());
            }
        }
    }

    private void completeStage(Run run, StageRuntime stage) {
        var spec = stage.spec();
        run.locked(() -> {
            var now = clock.instant();
            stage.recoveryTime(now).ifPresent(telemetry::recovered);
            if (spec.requiresApproval()) {
                requestApproval(run, stage);
            } else {
                stage.succeed(now);
                telemetry.emit(run.id, spec.id(), "stage_succeeded", Map.of("fallback", stage.toView().usedFallback()));
            }
        });
        if (spec.requiresApproval() && run.autoApprove) {
            try {
                approve(run.id, spec.id(), AUTO_APPROVER, "demo auto-approve");
            } catch (IllegalRunStateException e) {
                log.info("run {} auto-approve of {} skipped: {}", run.id, spec.id(), e.getMessage());
            }
        }
    }

    private void failStage(Run run, StageRuntime stage, String error) {
        run.locked(() -> {
            stage.fail(clock.instant(), error);
            telemetry.emit(run.id, stage.spec().id(), "stage_failed", Map.of("error", String.valueOf(error)));
            rollback(run, stage);
            run.markFailure(stage.spec().id() + ": " + error);
            run.signal();
        });
    }

    // ---------------------------------------------------------------- governance helpers (lock held)

    private void requestApproval(Run run, StageRuntime stage) {
        var spec = stage.spec();
        var warnings = run.ctx.lineage().values().stream()
                .filter(info -> info.producedBy().equals(spec.id()))
                .flatMap(info -> run.ctx.artifact(info.name()).stream())
                .flatMap(content -> warningsOf(content).stream())
                .toList();
        var approval = Approval.pending(shortId(), spec.id(), spec.approvalKind(), clock.instant(), warnings);
        run.approvals.add(approval);
        run.approvalsRequested++;
        stage.awaitApproval();
        telemetry.approvalRequested();
        decide(run, spec.id(), DecisionType.APPROVAL, "human checkpoint required for " + spec.approvalKind()
                        + (warnings.isEmpty() ? "" : "; warnings: " + warnings),
                "waiting_approval", SYSTEM, Map.of("approvalId", approval.id()));
        telemetry.emit(run.id, spec.id(), "approval_requested", Map.of("kind", spec.approvalKind().name(), "approvalId", approval.id()));
        run.signal();
    }

    private static List<String> warningsOf(Map<String, Object> artifact) {
        return artifact.get("warnings") instanceof List<?> warnings
                ? warnings.stream().map(String::valueOf).toList()
                : List.of();
    }

    private void rollback(Run run, StageRuntime stage) {
        var spec = stage.spec();
        if (spec.rollbackAgent() == null) {
            return;
        }
        // Compensating actions are short and deterministic, so they run under the lock to keep the transition atomic.
        try {
            agents.get(spec.rollbackAgent()).execute(stageContext(run, spec.id()));
            stage.rolledBack();
            run.rollbacks++;
            telemetry.rollback();
            decide(run, spec.id(), DecisionType.ROLLBACK, "rollback agent " + spec.rollbackAgent() + " executed",
                    "rolled_back", spec.rollbackAgent(), Map.of());
            telemetry.emit(run.id, spec.id(), "rolled_back", Map.of());
        } catch (RuntimeException e) {
            decide(run, spec.id(), DecisionType.ROLLBACK, "rollback failed: " + e.getMessage(), "rollback_failed",
                    spec.rollbackAgent(), Map.of());
            telemetry.emit(run.id, spec.id(), "rollback_failed", Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private List<String> invalidate(Run run, List<String> downstream) {
        var invalidated = new ArrayList<String>();
        for (var stageId : downstream) {
            var stage = run.stages.get(stageId);
            if (stage.state() == StageState.PENDING) {
                continue;
            }
            supersedeApprovals(run, stageId, "superseded by re-plan");
            if (stage.spec().approvalKind() == ApprovalKind.ASSUMPTION_ACCEPTANCE) {
                run.ctx.revokeAssumptions();
            }
            run.ctx.removeArtifactsProducedBy(stageId);
            stage.reset();
            invalidated.add(stageId);
            telemetry.emit(run.id, stageId, "stage_invalidated", Map.of());
        }
        return invalidated;
    }

    private void supersedeApprovals(Run run, String stageIdOrAll, String reason) {
        var now = clock.instant();
        for (int i = 0; i < run.approvals.size(); i++) {
            var approval = run.approvals.get(i);
            if (approval.isPending() && (stageIdOrAll == null || approval.stageId().equals(stageIdOrAll))) {
                run.approvals.set(i, approval.resolve(ApprovalStatus.SUPERSEDED, SYSTEM, reason, now));
            }
        }
    }

    private Approval pendingApproval(Run run, String stageId) {
        var pending = run.approvals.stream()
                .filter(Approval::isPending)
                .filter(a -> stageId == null || stageId.isBlank() || a.stageId().equals(stageId))
                .toList();
        if (pending.isEmpty()) {
            throw new IllegalRunStateException("no pending approval" + (stageId == null ? "" : " for stage " + stageId));
        }
        if (pending.size() > 1) {
            throw new IllegalRunStateException("several approvals pending; specify stageId");
        }
        return pending.getFirst();
    }

    private void replaceApproval(Run run, Approval updated) {
        for (int i = 0; i < run.approvals.size(); i++) {
            if (run.approvals.get(i).id().equals(updated.id())) {
                run.approvals.set(i, updated);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- run lifecycle (lock held)

    private void conclude(Run run) {
        var now = clock.instant();
        boolean stopped = run.failure == null && run.ctx.stopRequested();
        int skipped = 0;
        for (var stage : run.stages.values()) {
            if (stage.state() == StageState.PENDING || stage.state() == StageState.WAITING_APPROVAL) {
                if (stopped) {
                    stage.skip(now, "safe-stop");
                    skipped++;
                } else {
                    stage.block("upstream failure");
                }
            }
        }
        supersedeApprovals(run, null, stopped ? "safe-stop" : "run failed");
        if (stopped) {
            decide(run, "run", DecisionType.SAFE_STOP, "run stopped safely; " + skipped + " stages skipped", "stopped", SYSTEM, Map.of());
        }
        finish(run, stopped ? RunState.STOPPED : RunState.FAILED);
    }

    private void finish(Run run, RunState outcome) {
        if (run.state.isTerminal()) {
            return;
        }
        run.state = outcome;
        run.endedAt = clock.instant();
        run.endToEndMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - run.startNanos);
        telemetry.runFinished(outcome, Duration.ofMillis(run.endToEndMs));
        telemetry.emit(run.id, null, "run_finished",
                Map.of("state", outcome.name(), "error", Objects.toString(run.failure, "none"), "e2eMs", run.endToEndMs));
        run.signal();
    }

    private void transition(Run run, RunState next) {
        if (run.state != next) {
            run.state = next;
            telemetry.emit(run.id, null, "run_" + next.name().toLowerCase(), Map.of());
            run.signal();
        }
    }

    /**
     * After a human action the scheduler must re-evaluate before anyone treats the run as settled.
     */
    private void resume(Run run) {
        run.state = RunState.RUNNING;
        run.signal();
    }

    private void ensureActive(Run run) {
        if (run.state.isTerminal()) {
            throw new IllegalRunStateException("run " + run.id + " is already " + run.state);
        }
        if (run.failure != null || run.ctx.stopRequested()) {
            throw new IllegalRunStateException("run " + run.id + " is winding down");
        }
    }

    // ---------------------------------------------------------------- plumbing

    private StageContext stageContext(Run run, String stageId) {
        return new StageContext(run.ctx, stageId, (producer, name, content) -> publish(run, producer, name, content));
    }

    /**
     * Single choke point for artifact writes: policy scan, content hash, provenance, audit event.
     */
    private void publish(Run run, String stageId, String name, Map<String, Object> content) {
        policy.checkArtifact(name, content);
        var sha = policy.fingerprint(content);
        var info = run.ctx.store(name, content, stageId, sha, clock.instant());
        telemetry.emit(run.id, stageId, "artifact_published", Map.of("artifact", name, "sha256", sha, "version", info.version()));
    }

    private void ensureNotHalted(Run run) {
        if (run.ctx.stopRequested()) {
            throw new StageCancelledException("safe-stop");
        }
        if (run.locked(() -> run.failure != null)) {
            throw new StageCancelledException("run failing; no new attempts");
        }
    }

    private static void backoff(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StageCancelledException("interrupted during backoff");
        }
    }

    private void decide(Run run, String stageId, DecisionType type, String rationale, String outcome, String actor,
                        Map<String, Object> inputs) {
        var record = new DecisionRecord(shortId(), clock.instant(), stageId, type, rationale, outcome, actor, Map.copyOf(inputs));
        run.locked(() -> run.decisions.add(record));
        // Decisions also go to the durable audit trail so lineage outlives the in-memory run.
        telemetry.emit(run.id, "run".equals(stageId) ? null : stageId, "decision", Map.of(
                "type", type.name(), "outcome", outcome, "actor", actor, "rationale", rationale));
    }

    private RunView view(Run run) {
        return run.locked(() -> new RunView(
                run.id, run.scenario, run.state, run.failure, run.initiator, run.autoApprove, run.ctx.failureMode(),
                run.startedAt, run.endedAt,
                run.stages.values().stream().map(StageRuntime::toView).toList(),
                run.ctx.artifacts(), run.ctx.lineage(),
                List.copyOf(run.approvals), List.copyOf(run.decisions), run.ctx.policyViolations(),
                new RunView.RunMetrics(run.retries, run.fallbacks, run.rollbacks, run.replans, run.approvalsRequested,
                        run.stages.values().stream().filter(s -> s.state() == StageState.SUCCEEDED).count(),
                        run.stages.values().stream().filter(s -> s.state().isFailure()).count(),
                        run.endToEndMs)));
    }

    private Run require(String runId) {
        var run = runs.get(runId);
        if (run == null) {
            throw new RunNotFoundException(runId);
        }
        return run;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Raised inside a worker when the run is stopping or failing; the stage is skipped, not failed.
     */
    private static final class StageCancelledException extends RuntimeException {
        StageCancelledException(String reason) {
            super(reason);
        }
    }
}
