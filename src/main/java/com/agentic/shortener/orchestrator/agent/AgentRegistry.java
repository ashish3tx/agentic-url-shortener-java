package com.agentic.shortener.orchestrator.agent;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AgentRegistry {

    private final Map<String, Agent> agents = new HashMap<>();

    public AgentRegistry(List<Agent> discovered) {
        for (var agent : discovered) {
            if (agents.putIfAbsent(agent.id(), agent) != null) {
                throw new IllegalStateException("duplicate agent id: " + agent.id());
            }
        }
    }

    public Agent get(String id) {
        var agent = agents.get(id);
        if (agent == null) {
            throw new IllegalArgumentException("no agent registered for id " + id);
        }
        return agent;
    }

    public boolean contains(String id) {
        return agents.containsKey(id);
    }

    public Set<String> ids() {
        return Set.copyOf(agents.keySet());
    }
}
