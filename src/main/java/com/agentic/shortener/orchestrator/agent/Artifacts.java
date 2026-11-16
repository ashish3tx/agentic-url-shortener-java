package com.agentic.shortener.orchestrator.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small helpers so agents can build ordered, JSON-friendly artifacts without ceremony.
 */
final class Artifacts {

    private Artifacts() {
    }

    static Map<String, Object> ordered(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("key/value pairs expected");
        }
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    static List<String> strings(Map<String, Object> artifact, String key) {
        if (artifact.get(key) instanceof List<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
