package com.agentic.shortener.orchestrator.graph;

import com.agentic.shortener.orchestrator.agent.AgentRegistry;
import com.agentic.shortener.orchestrator.model.Scenario;
import com.agentic.shortener.orchestrator.model.StageSpec;
import com.agentic.shortener.orchestrator.policy.PolicyEngine;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Builds and validates every scenario graph at startup, so a mis-wired graph fails the deploy, not a run.
 */
@Component
public class ScenarioCatalog {

    private final Map<Scenario, StageGraph> graphs = new EnumMap<>(Scenario.class);

    public ScenarioCatalog(AgentRegistry agents, PolicyEngine policy) {
        for (var scenario : Scenario.values()) {
            var graph = ScenarioGraphs.forScenario(scenario);
            policy.checkGraph(graph);
            graph.stages().forEach(spec -> referencedAgents(spec).forEach(agentId -> {
                if (!agents.contains(agentId)) {
                    throw new IllegalStateException(scenario + "/" + spec.id() + " references unknown agent " + agentId);
                }
            }));
            graphs.put(scenario, graph);
        }
    }

    public StageGraph graph(Scenario scenario) {
        return graphs.get(scenario);
    }

    private static Stream<String> referencedAgents(StageSpec spec) {
        return Stream.of(spec.agent(), spec.fallbackAgent(), spec.rollbackAgent()).filter(Objects::nonNull);
    }
}
