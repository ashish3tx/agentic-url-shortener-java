package com.agentic.shortener.orchestrator.graph;

import com.agentic.shortener.orchestrator.model.StageSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validated, immutable DAG of stages. Construction fails fast on duplicate ids, unknown
 * dependencies and cycles, so a broken scenario never reaches the engine.
 */
public final class StageGraph {

    private final Map<String, StageSpec> stages;
    private final Map<String, List<String>> children;
    private final List<String> topologicalOrder;

    public StageGraph(List<StageSpec> specs) {
        var byId = new LinkedHashMap<String, StageSpec>();
        for (var spec : specs) {
            if (byId.putIfAbsent(spec.id(), spec) != null) {
                throw new IllegalArgumentException("duplicate stage id: " + spec.id());
            }
        }
        var childMap = new HashMap<String, List<String>>();
        byId.keySet().forEach(id -> childMap.put(id, new ArrayList<>()));
        for (var spec : byId.values()) {
            if (Set.copyOf(spec.dependsOn()).size() != spec.dependsOn().size()) {
                throw new IllegalArgumentException("stage " + spec.id() + " lists a dependency twice");
            }
            for (var dependency : spec.dependsOn()) {
                if (!byId.containsKey(dependency)) {
                    throw new IllegalArgumentException("stage " + spec.id() + " depends on unknown stage " + dependency);
                }
                childMap.get(dependency).add(spec.id());
            }
        }
        this.children = childMap;
        this.topologicalOrder = topologicalSort(byId);
        var ordered = new LinkedHashMap<String, StageSpec>();
        topologicalOrder.forEach(id -> ordered.put(id, byId.get(id)));
        this.stages = ordered;
    }

    /**
     * Stages in a valid execution order (dependencies first).
     */
    public List<StageSpec> stages() {
        return List.copyOf(stages.values());
    }

    public StageSpec stage(String id) {
        var spec = stages.get(id);
        if (spec == null) {
            throw new IllegalArgumentException("unknown stage: " + id);
        }
        return spec;
    }

    /**
     * Every stage transitively downstream of {@code id}, in topological order.
     */
    public List<String> descendantsOf(String id) {
        var seen = new LinkedHashSet<String>();
        var queue = new ArrayDeque<>(children.getOrDefault(id, List.of()));
        while (!queue.isEmpty()) {
            var next = queue.poll();
            if (seen.add(next)) {
                queue.addAll(children.get(next));
            }
        }
        return topologicalOrder.stream().filter(seen::contains).toList();
    }

    private static List<String> topologicalSort(Map<String, StageSpec> byId) {
        // Kahn's algorithm; leftover nodes mean a cycle.
        var inDegree = new HashMap<String, Integer>();
        byId.values().forEach(s -> inDegree.put(s.id(), s.dependsOn().size()));
        var ready = new ArrayDeque<String>();
        byId.keySet().stream().filter(id -> inDegree.get(id) == 0).forEach(ready::add);
        var order = new ArrayList<String>();
        while (!ready.isEmpty()) {
            var id = ready.poll();
            order.add(id);
            for (var spec : byId.values()) {
                if (spec.dependsOn().contains(id) && inDegree.merge(spec.id(), -1, Integer::sum) == 0) {
                    ready.add(spec.id());
                }
            }
        }
        if (order.size() != byId.size()) {
            Set<String> cyclic = new LinkedHashSet<>(byId.keySet());
            order.forEach(cyclic::remove);
            throw new IllegalArgumentException("stage graph has a cycle involving " + cyclic);
        }
        return List.copyOf(order);
    }
}
