package com.agentic.shortener.orchestrator.telemetry;

import com.agentic.shortener.orchestrator.model.RunState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Audit trail plus reliability metrics. Events are persisted through {@link AuditTrail} and
 * mirrored to the 'audit.orchestrator' logger; aggregates are Micrometer meters (Prometheus-ready).
 */
@Component
public class OrchestratorTelemetry {

    private static final Logger AUDIT = LoggerFactory.getLogger("audit.orchestrator");

    private final AuditTrail auditTrail;
    private final Clock clock;
    private final Counter runsStarted;
    private final Map<RunState, Counter> runOutcomes = new EnumMap<>(RunState.class);
    private final Counter retries;
    private final Counter fallbacks;
    private final Counter rollbacks;
    private final Counter replans;
    private final Counter approvalsRequested;
    private final Counter auditWriteFailures;
    private final Timer runLatency;
    private final Timer recoveryTime;

    public OrchestratorTelemetry(AuditTrail auditTrail, MeterRegistry registry, Clock clock) {
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.runsStarted = registry.counter("orchestrator.runs.started");
        for (var state : List.of(RunState.SUCCEEDED, RunState.FAILED, RunState.STOPPED)) {
            runOutcomes.put(state, registry.counter("orchestrator.runs.finished", "outcome", state.name().toLowerCase(Locale.ROOT)));
        }
        this.retries = registry.counter("orchestrator.stage.retries");
        this.fallbacks = registry.counter("orchestrator.stage.fallbacks");
        this.rollbacks = registry.counter("orchestrator.stage.rollbacks");
        this.replans = registry.counter("orchestrator.replans");
        this.approvalsRequested = registry.counter("orchestrator.approvals.requested");
        this.auditWriteFailures = registry.counter("orchestrator.audit.write.failures");
        this.runLatency = registry.timer("orchestrator.run.latency");
        this.recoveryTime = registry.timer("orchestrator.stage.recovery");
    }

    public void emit(String runId, String stageId, String event, Map<String, Object> detail) {
        var trace = new TraceEvent(0, clock.instant(), runId, stageId, event, Map.copyOf(detail));
        AUDIT.info("run={} stage={} event={} detail={}", runId, stageId == null ? "-" : stageId, event, detail);
        try {
            auditTrail.append(trace);
        } catch (RuntimeException e) {
            // Fail open so a database blip cannot wedge a run mid-stage; the log line above and
            // the failure counter (alert on it) keep the gap visible.
            auditWriteFailures.increment();
            AUDIT.error("audit write failed for run={} event={}", runId, event, e);
        }
    }

    public List<TraceEvent> trace(String runId) {
        return auditTrail.forRun(runId);
    }

    public void runStarted() {
        runsStarted.increment();
    }

    public void runFinished(RunState outcome, Duration endToEnd) {
        var counter = runOutcomes.get(outcome);
        if (counter != null) {
            counter.increment();
        }
        runLatency.record(endToEnd);
    }

    public void retry() {
        retries.increment();
    }

    public void fallback() {
        fallbacks.increment();
    }

    public void rollback() {
        rollbacks.increment();
    }

    public void replan() {
        replans.increment();
    }

    public void approvalRequested() {
        approvalsRequested.increment();
    }

    /** MTTR sample: time from a stage's first failed attempt until it recovered (retry or fallback). */
    public void recovered(Duration timeToRecover) {
        recoveryTime.record(timeToRecover);
    }

    public ReliabilityMetrics snapshot() {
        long succeeded = count(RunState.SUCCEEDED);
        long failed = count(RunState.FAILED);
        long stopped = count(RunState.STOPPED);
        long finished = succeeded + failed + stopped;
        return new ReliabilityMetrics(
                (long) runsStarted.count(), succeeded, failed, stopped,
                finished == 0 ? 0.0 : round((double) succeeded / finished),
                (long) retries.count(), (long) fallbacks.count(), (long) rollbacks.count(), (long) replans.count(),
                (long) approvalsRequested.count(),
                recoveryTime.count(), round(recoveryTime.mean(TimeUnit.MILLISECONDS)),
                round(runLatency.mean(TimeUnit.MILLISECONDS)),
                clock.instant());
    }

    private long count(RunState state) {
        return (long) runOutcomes.get(state).count();
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
