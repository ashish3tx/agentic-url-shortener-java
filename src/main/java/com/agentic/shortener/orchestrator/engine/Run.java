package com.agentic.shortener.orchestrator.engine;

import com.agentic.shortener.orchestrator.graph.StageGraph;
import com.agentic.shortener.orchestrator.model.Approval;
import com.agentic.shortener.orchestrator.model.DecisionRecord;
import com.agentic.shortener.orchestrator.model.RunContext;
import com.agentic.shortener.orchestrator.model.RunState;
import com.agentic.shortener.orchestrator.model.Scenario;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * State of one orchestration run. A single ReentrantLock guards all mutable fields; the
 * {@code changed} condition wakes the scheduler loop and any waiters on every transition.
 * ReentrantLock rather than synchronized so virtual threads never pin their carrier while waiting.
 */
final class Run {

    final String id;
    final Scenario scenario;
    final StageGraph graph;
    final String initiator;
    final boolean autoApprove;
    final RunContext ctx;
    final Instant startedAt;
    final long startNanos = System.nanoTime();

    final Map<String, StageRuntime> stages = new LinkedHashMap<>();
    final List<DecisionRecord> decisions = new ArrayList<>();
    final List<Approval> approvals = new ArrayList<>();
    final Set<String> inFlight = new HashSet<>();

    final ReentrantLock lock = new ReentrantLock();
    final Condition changed = lock.newCondition();

    RunState state = RunState.RUNNING;
    String failure;
    Instant endedAt;
    Long endToEndMs;
    int retries;
    int fallbacks;
    int rollbacks;
    int replans;
    int approvalsRequested;

    Run(String id, Scenario scenario, StageGraph graph, String initiator, boolean autoApprove, RunContext ctx, Instant startedAt) {
        this.id = id;
        this.scenario = scenario;
        this.graph = graph;
        this.initiator = initiator;
        this.autoApprove = autoApprove;
        this.ctx = ctx;
        this.startedAt = startedAt;
        graph.stages().forEach(spec -> stages.put(spec.id(), new StageRuntime(spec)));
    }

    <T> T locked(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    void locked(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Caller must hold the lock.
     */
    void signal() {
        changed.signalAll();
    }

    /**
     * Records the first failure only; later ones are consequences, not causes.
     */
    void markFailure(String reason) {
        if (failure == null) {
            failure = reason;
        }
    }

    boolean hasPendingApproval() {
        return approvals.stream().anyMatch(Approval::isPending);
    }

    boolean allStagesTerminal() {
        return stages.values().stream().allMatch(s -> s.state().isTerminal());
    }

    boolean isSettled() {
        return state.isTerminal() || (state == RunState.WAITING_APPROVAL && inFlight.isEmpty());
    }
}
