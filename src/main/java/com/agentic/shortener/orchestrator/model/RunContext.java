package com.agentic.shortener.orchestrator.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Cross-stage memory of a run. Parallel stages read and write here concurrently, so every
 * structure is thread-safe and artifacts are stored as unmodifiable snapshots.
 */
public final class RunContext {

    private final String runId;
    private final Scenario scenario;
    private final FailureMode failureMode;
    private final Map<String, Map<String, Object>> artifacts = new ConcurrentHashMap<>();
    private final Map<String, ArtifactInfo> lineage = new ConcurrentHashMap<>();
    private final List<String> policyViolations = new CopyOnWriteArrayList<>();
    private final AtomicBoolean assumptionsAccepted = new AtomicBoolean();
    private final AtomicInteger contractSuiteRuns = new AtomicInteger();
    private volatile boolean stopRequested;

    public RunContext(String runId, Scenario scenario, FailureMode failureMode) {
        this.runId = runId;
        this.scenario = scenario;
        this.failureMode = failureMode;
    }

    public String runId() {
        return runId;
    }

    public Scenario scenario() {
        return scenario;
    }

    public FailureMode failureMode() {
        return failureMode;
    }

    public Optional<Map<String, Object>> artifact(String name) {
        return Optional.ofNullable(artifacts.get(name));
    }

    public Map<String, Map<String, Object>> artifactsWithPrefix(String prefix) {
        return artifacts.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new));
    }

    public ArtifactInfo store(String name, Map<String, Object> content, String producedBy, String sha256, Instant at) {
        artifacts.put(name, Collections.unmodifiableMap(new LinkedHashMap<>(content)));
        return lineage.compute(name, (key, previous) ->
                new ArtifactInfo(name, producedBy, sha256, previous == null ? 1 : previous.version() + 1, at));
    }

    public Optional<ArtifactInfo> lineage(String name) {
        return Optional.ofNullable(lineage.get(name));
    }

    public Map<String, ArtifactInfo> lineage() {
        return new TreeMap<>(lineage);
    }

    public Map<String, Map<String, Object>> artifacts() {
        return new TreeMap<>(artifacts);
    }

    /**
     * Invalidates whatever a stage produced so re-planned downstream work never reads stale output.
     */
    public Set<String> removeArtifactsProducedBy(String stageId) {
        var names = lineage.values().stream()
                .filter(info -> info.producedBy().equals(stageId))
                .map(ArtifactInfo::name)
                .collect(Collectors.toSet());
        names.forEach(artifacts::remove);
        return names;
    }

    public void recordPolicyViolation(String violation) {
        policyViolations.add(violation);
    }

    public List<String> policyViolations() {
        return List.copyOf(policyViolations);
    }

    public boolean assumptionsAccepted() {
        return assumptionsAccepted.get();
    }

    public void acceptAssumptions() {
        assumptionsAccepted.set(true);
    }

    public void revokeAssumptions() {
        assumptionsAccepted.set(false);
    }

    public int nextContractSuiteRun() {
        return contractSuiteRuns.incrementAndGet();
    }

    public boolean stopRequested() {
        return stopRequested;
    }

    public void requestStop() {
        stopRequested = true;
    }
}
