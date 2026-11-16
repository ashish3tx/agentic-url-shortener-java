package com.agentic.shortener.orchestrator.model;

import com.agentic.shortener.orchestrator.gate.Gate;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Declarative definition of one node in the SDLC graph: what runs, what it waits for,
 * which gates guard it, how much autonomy the agent has, and how failure is handled.
 */
public record StageSpec(
        String id,
        String name,
        StageKind kind,
        String agent,
        List<String> dependsOn,
        JoinType join,
        List<Gate> entryGates,
        List<Gate> exitGates,
        Autonomy autonomy,
        ApprovalKind approvalKind,
        int maxAttempts,
        Duration backoff,
        Duration timeout,
        String fallbackAgent,
        String rollbackAgent,
        String parallelGroup) {

    public StageSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(agent, "agent");
        dependsOn = List.copyOf(dependsOn);
        entryGates = List.copyOf(entryGates);
        exitGates = List.copyOf(exitGates);
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1 for stage " + id);
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive for stage " + id);
        }
    }

    public boolean requiresApproval() {
        return approvalKind != null;
    }

    public static Builder stage(String id, String name, StageKind kind) {
        return new Builder(id, name, kind);
    }

    public static final class Builder {
        private final String id;
        private final String name;
        private final StageKind kind;
        private String agent;
        private List<String> dependsOn = List.of();
        private JoinType join = JoinType.ALL;
        private List<Gate> entryGates = List.of();
        private List<Gate> exitGates = List.of();
        private Autonomy autonomy = Autonomy.SUPERVISED;
        private ApprovalKind approvalKind;
        private int maxAttempts = 3;
        private Duration backoff = Duration.ofMillis(25);
        private Duration timeout = Duration.ofSeconds(30);
        private String fallbackAgent;
        private String rollbackAgent;
        private String parallelGroup;

        private Builder(String id, String name, StageKind kind) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.agent = id;
        }

        public Builder agent(String agentId) {
            this.agent = agentId;
            return this;
        }

        public Builder after(String... upstream) {
            this.dependsOn = List.of(upstream);
            return this;
        }

        public Builder join(JoinType joinType) {
            this.join = joinType;
            return this;
        }

        public Builder entry(Gate... gates) {
            this.entryGates = List.of(gates);
            return this;
        }

        public Builder exit(Gate... gates) {
            this.exitGates = List.of(gates);
            return this;
        }

        public Builder autonomy(Autonomy level) {
            this.autonomy = level;
            return this;
        }

        public Builder humanApproval(ApprovalKind kind) {
            this.approvalKind = kind;
            this.autonomy = Autonomy.HUMAN_GATED;
            return this;
        }

        public Builder retries(int attempts, Duration initialBackoff) {
            this.maxAttempts = attempts;
            this.backoff = initialBackoff;
            return this;
        }

        /** Upper bound for one agent invocation; a hung agent counts as a failed attempt. */
        public Builder timeout(Duration limit) {
            this.timeout = limit;
            return this;
        }

        public Builder fallback(String agentId) {
            this.fallbackAgent = agentId;
            return this;
        }

        public Builder rollback(String agentId) {
            this.rollbackAgent = agentId;
            return this;
        }

        public Builder parallel(String group) {
            this.parallelGroup = group;
            return this;
        }

        public StageSpec build() {
            return new StageSpec(id, name, kind, agent, dependsOn, join, entryGates, exitGates, autonomy,
                    approvalKind, maxAttempts, backoff, timeout, fallbackAgent, rollbackAgent, parallelGroup);
        }
    }
}
