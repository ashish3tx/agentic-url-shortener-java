package com.agentic.shortener.orchestrator.model;

import java.util.Map;
import java.util.Optional;

/**
 * What an agent sees while it executes one stage. Writes go through {@link ArtifactPublisher}
 * so every artifact is policy-checked, hashed and attributed to the producing stage.
 */
public final class StageContext {

    @FunctionalInterface
    public interface ArtifactPublisher {
        void publish(String stageId, String name, Map<String, Object> content);
    }

    private final RunContext run;
    private final String stageId;
    private final ArtifactPublisher publisher;

    public StageContext(RunContext run, String stageId, ArtifactPublisher publisher) {
        this.run = run;
        this.stageId = stageId;
        this.publisher = publisher;
    }

    public String stageId() {
        return stageId;
    }

    public Scenario scenario() {
        return run.scenario();
    }

    public FailureMode failureMode() {
        return run.failureMode();
    }

    public Optional<Map<String, Object>> artifact(String name) {
        return run.artifact(name);
    }

    public Map<String, Object> requireArtifact(String name) {
        return run.artifact(name).orElseThrow(() -> new IllegalStateException("required artifact '" + name + "' missing"));
    }

    public Map<String, Map<String, Object>> artifactsWithPrefix(String prefix) {
        return run.artifactsWithPrefix(prefix);
    }

    public Optional<ArtifactInfo> lineage(String name) {
        return run.lineage(name);
    }

    public boolean assumptionsAccepted() {
        return run.assumptionsAccepted();
    }

    public boolean policyClean() {
        return run.policyViolations().isEmpty();
    }

    public int nextContractSuiteRun() {
        return run.nextContractSuiteRun();
    }

    public void publish(String name, Map<String, Object> content) {
        publisher.publish(stageId, name, content);
    }
}
