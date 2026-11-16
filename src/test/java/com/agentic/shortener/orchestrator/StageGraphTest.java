package com.agentic.shortener.orchestrator;

import com.agentic.shortener.orchestrator.graph.ScenarioGraphs;
import com.agentic.shortener.orchestrator.graph.StageGraph;
import com.agentic.shortener.orchestrator.model.Autonomy;
import com.agentic.shortener.orchestrator.model.Scenario;
import com.agentic.shortener.orchestrator.model.StageKind;
import com.agentic.shortener.orchestrator.model.StageSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static com.agentic.shortener.orchestrator.model.StageSpec.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StageGraphTest {

    @Test
    void rejectsCyclesUnknownDependenciesAndDuplicates() {
        assertThatThrownBy(() -> new StageGraph(List.of(
                stage("a", "A", StageKind.SYNC).after("b").build(),
                stage("b", "B", StageKind.SYNC).after("a").build())))
                .hasMessageContaining("cycle");
        assertThatThrownBy(() -> new StageGraph(List.of(stage("a", "A", StageKind.SYNC).after("ghost").build())))
                .hasMessageContaining("unknown stage ghost");
        assertThatThrownBy(() -> new StageGraph(List.of(
                stage("a", "A", StageKind.SYNC).build(), stage("a", "A2", StageKind.SYNC).build())))
                .hasMessageContaining("duplicate");
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void everyScenarioHasParallelImplementationJoinedBeforeTesting(Scenario scenario) {
        var graph = ScenarioGraphs.forScenario(scenario);
        var parallel = graph.stages().stream().filter(s -> "implementation".equals(s.parallelGroup())).toList();

        assertThat(parallel).hasSize(3);
        assertThat(graph.stage("sync_impl").dependsOn()).containsExactlyInAnyOrderElementsOf(parallel.stream().map(StageSpec::id).toList());
        assertThat(graph.stage("release").dependsOn()).containsExactlyInAnyOrder("testing", "documentation");
        assertThat(graph.stage("release").autonomy()).isEqualTo(Autonomy.HUMAN_GATED);
    }

    @Test
    void topologicalOrderAndDescendants() {
        var graph = ScenarioGraphs.forScenario(Scenario.BROWNFIELD);
        var order = graph.stages().stream().map(StageSpec::id).toList();

        assertThat(order.indexOf("impact")).isLessThan(order.indexOf("architecture"));
        assertThat(order.getLast()).isEqualTo("release");
        assertThat(graph.descendantsOf("testing")).containsExactly("release");
        assertThat(graph.descendantsOf("requirements")).hasSize(order.size() - 1).doesNotContain("requirements");
    }

    @Test
    void ambiguousScenarioGatesArchitectureOnHumanAcceptance() {
        var graph = ScenarioGraphs.forScenario(Scenario.AMBIGUOUS);
        assertThat(graph.stage("normalize").requiresApproval()).isTrue();
        assertThat(graph.stage("architecture").dependsOn()).containsExactly("normalize");
    }
}
