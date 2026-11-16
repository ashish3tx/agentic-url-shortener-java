package com.agentic.shortener.orchestrator.graph;

import com.agentic.shortener.orchestrator.model.ApprovalKind;
import com.agentic.shortener.orchestrator.model.Autonomy;
import com.agentic.shortener.orchestrator.model.Scenario;
import com.agentic.shortener.orchestrator.model.StageSpec;

import java.time.Duration;
import java.util.List;

import static com.agentic.shortener.orchestrator.gate.Gate.AMBIGUITY_RESOLVED;
import static com.agentic.shortener.orchestrator.gate.Gate.ARCHITECTURE_PRESENT;
import static com.agentic.shortener.orchestrator.gate.Gate.DOCS_PRESENT;
import static com.agentic.shortener.orchestrator.gate.Gate.IMPACT_MAPPED;
import static com.agentic.shortener.orchestrator.gate.Gate.IMPLEMENTATION_COMPLETE;
import static com.agentic.shortener.orchestrator.gate.Gate.NO_POLICY_VIOLATIONS;
import static com.agentic.shortener.orchestrator.gate.Gate.RELEASE_READY;
import static com.agentic.shortener.orchestrator.gate.Gate.REQUIREMENTS_COMPLETE;
import static com.agentic.shortener.orchestrator.gate.Gate.TESTS_GREEN;
import static com.agentic.shortener.orchestrator.model.StageKind.ARCHITECTURE;
import static com.agentic.shortener.orchestrator.model.StageKind.DOCUMENTATION;
import static com.agentic.shortener.orchestrator.model.StageKind.IMPACT_ANALYSIS;
import static com.agentic.shortener.orchestrator.model.StageKind.IMPLEMENTATION;
import static com.agentic.shortener.orchestrator.model.StageKind.RELEASE;
import static com.agentic.shortener.orchestrator.model.StageKind.REQUIREMENTS;
import static com.agentic.shortener.orchestrator.model.StageKind.SYNC;
import static com.agentic.shortener.orchestrator.model.StageKind.TESTING;
import static com.agentic.shortener.orchestrator.model.StageSpec.stage;

/**
 * The three SDLC graphs. They share a spine (requirements → design → parallel implementation →
 * join → test ∥ docs → gated release) but differ where the scenario demands it: brownfield adds
 * codebase impact analysis, ambiguous adds a human assumption-acceptance checkpoint.
 */
public final class ScenarioGraphs {

    private ScenarioGraphs() {
    }

    public static StageGraph forScenario(Scenario scenario) {
        return switch (scenario) {
            case GREENFIELD -> greenfield();
            case BROWNFIELD -> brownfield();
            case AMBIGUOUS -> ambiguous();
        };
    }

    private static StageGraph greenfield() {
        return new StageGraph(List.of(
                stage("requirements", "Normalize greenfield requirements", REQUIREMENTS)
                        .exit(REQUIREMENTS_COMPLETE).autonomy(Autonomy.FULL).build(),
                stage("architecture", "Design shortener architecture", ARCHITECTURE)
                        .after("requirements").entry(REQUIREMENTS_COMPLETE).exit(ARCHITECTURE_PRESENT).build(),
                implementation("impl_api", "Implement REST API"),
                implementation("impl_store", "Implement persistence"),
                implementation("impl_analytics", "Implement click analytics"),
                sync("impl_api", "impl_store", "impl_analytics"),
                testing(),
                documentation("Generate API and ops documentation"),
                release("Release readiness + production deploy", ApprovalKind.PRODUCTION_DEPLOY)));
    }

    private static StageGraph brownfield() {
        return new StageGraph(List.of(
                stage("requirements", "Normalize enhancement request", REQUIREMENTS)
                        .exit(REQUIREMENTS_COMPLETE).autonomy(Autonomy.FULL).build(),
                stage("impact", "Codebase impact analysis", IMPACT_ANALYSIS)
                        .agent("impact_analysis").after("requirements")
                        .entry(REQUIREMENTS_COMPLETE).exit(IMPACT_MAPPED).build(),
                stage("architecture", "Design additive change", ARCHITECTURE)
                        .after("impact").entry(IMPACT_MAPPED).exit(ARCHITECTURE_PRESENT).build(),
                implementation("impl_alias", "Custom alias path"),
                implementation("impl_ttl", "Expiry / TTL path"),
                implementation("impl_ratelimit", "Write-path rate limiting"),
                sync("impl_alias", "impl_ttl", "impl_ratelimit"),
                testing(),
                documentation("Update API contract and changelog"),
                release("Public API change control", ApprovalKind.PUBLIC_API)));
    }

    private static StageGraph ambiguous() {
        return new StageGraph(List.of(
                stage("requirements", "Discover ambiguities and propose a bounded interpretation", REQUIREMENTS)
                        .exit(REQUIREMENTS_COMPLETE).build(),
                stage("normalize", "Human accepts bounded interpretation", REQUIREMENTS)
                        .agent("normalize_scope").after("requirements").entry(REQUIREMENTS_COMPLETE)
                        .humanApproval(ApprovalKind.ASSUMPTION_ACCEPTANCE).build(),
                stage("architecture", "Enterprise control architecture", ARCHITECTURE)
                        .after("normalize").entry(AMBIGUITY_RESOLVED).exit(ARCHITECTURE_PRESENT).build(),
                implementation("impl_ssrf", "SSRF / scheme guardrails"),
                implementation("impl_audit", "Audit trail and privacy-safe analytics"),
                implementation("impl_retention", "Click retention control"),
                sync("impl_ssrf", "impl_audit", "impl_retention"),
                testing(),
                documentation("Risks, assumptions and limitations"),
                release("Controlled production readiness", ApprovalKind.PRODUCTION_DEPLOY)));
    }

    private static StageSpec implementation(String id, String name) {
        return stage(id, name, IMPLEMENTATION)
                .after("architecture").entry(ARCHITECTURE_PRESENT).parallel("implementation").build();
    }

    private static StageSpec sync(String... streams) {
        return stage("sync_impl", "Join implementation streams", SYNC)
                .agent("integration_sync").after(streams).exit(IMPLEMENTATION_COMPLETE)
                .autonomy(Autonomy.FULL).build();
    }

    private static StageSpec testing() {
        return stage("testing", "Contract test suite", TESTING)
                .agent("contract_tests").after("sync_impl")
                .entry(IMPLEMENTATION_COMPLETE).exit(TESTS_GREEN)
                .retries(3, Duration.ofMillis(50)).timeout(Duration.ofSeconds(5)).fallback("smoke_tests")
                .parallel("verification").build();
    }

    private static StageSpec documentation(String name) {
        return stage("documentation", name, DOCUMENTATION)
                .after("sync_impl").entry(IMPLEMENTATION_COMPLETE).exit(DOCS_PRESENT)
                .autonomy(Autonomy.FULL).parallel("verification").build();
    }

    private static StageSpec release(String name, ApprovalKind approval) {
        return stage("release", name, RELEASE)
                .after("testing", "documentation")
                .entry(TESTS_GREEN, DOCS_PRESENT, NO_POLICY_VIOLATIONS).exit(RELEASE_READY)
                .humanApproval(approval).retries(1, Duration.ZERO).rollback("rollback_release").build();
    }
}
