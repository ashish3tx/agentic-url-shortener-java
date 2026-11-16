package com.agentic.shortener.orchestrator.engine;

import com.agentic.shortener.orchestrator.model.StageSpec;
import com.agentic.shortener.orchestrator.model.StageState;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Mutable execution state of one stage. Every mutation happens under the owning run's lock.
 */
final class StageRuntime {

    private final StageSpec spec;
    private StageState state = StageState.PENDING;
    private int attempts;
    private boolean usedFallback;
    private Instant startedAt;
    private Instant endedAt;
    private Instant firstFailureAt;
    private String error;

    StageRuntime(StageSpec spec) {
        this.spec = spec;
    }

    StageSpec spec() {
        return spec;
    }

    StageState state() {
        return state;
    }

    String error() {
        return error;
    }

    void start(Instant now) {
        state = StageState.RUNNING;
        startedAt = now;
        endedAt = null;
        error = null;
    }

    void recordAttempt() {
        attempts++;
    }

    void recordFailure(Instant now, String message) {
        if (firstFailureAt == null) {
            firstFailureAt = now;
        }
        error = message;
    }

    void markFallback() {
        usedFallback = true;
    }

    /**
     * Time since the first failed attempt, if this stage recovered from one.
     */
    Optional<Duration> recoveryTime(Instant now) {
        return Optional.ofNullable(firstFailureAt).map(failedAt -> Duration.between(failedAt, now));
    }

    void awaitApproval() {
        state = StageState.WAITING_APPROVAL;
        error = null;
    }

    void succeed(Instant now) {
        state = StageState.SUCCEEDED;
        endedAt = now;
        error = null;
    }

    void fail(Instant now, String message) {
        state = StageState.FAILED;
        endedAt = now;
        error = message;
    }

    void rolledBack() {
        state = StageState.ROLLED_BACK;
    }

    void skip(Instant now, String reason) {
        state = StageState.SKIPPED;
        endedAt = now;
        error = reason;
    }

    void block(String reason) {
        state = StageState.BLOCKED;
        error = reason;
    }

    /**
     * Re-plan: forget previous execution so the stage runs again with fresh upstream context.
     */
    void reset() {
        state = StageState.PENDING;
        attempts = 0;
        usedFallback = false;
        startedAt = null;
        endedAt = null;
        firstFailureAt = null;
        error = null;
    }

    StageView toView() {
        return new StageView(spec.id(), spec.name(), spec.kind(), spec.agent(), spec.dependsOn(), spec.join(),
                spec.parallelGroup(), spec.autonomy(), spec.approvalKind(), spec.entryGates(), spec.exitGates(),
                spec.maxAttempts(), spec.fallbackAgent(), spec.rollbackAgent(),
                state, attempts, usedFallback, startedAt, endedAt, error);
    }
}
