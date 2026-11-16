package com.agentic.shortener.orchestrator.agent;

import com.agentic.shortener.orchestrator.model.StageContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.agentic.shortener.orchestrator.agent.Artifacts.ordered;

/**
 * Join barrier: merges the parallel implementation streams and refuses to pass a partial result.
 */
@Component
public class IntegrationSyncAgent implements Agent {

    private static final String PREFIX = "impl.";

    @Override
    public String id() {
        return "integration_sync";
    }

    @Override
    public void execute(StageContext ctx) {
        var parts = new LinkedHashMap<String, Object>();
        ctx.artifactsWithPrefix(PREFIX).forEach((name, content) -> parts.put(name.substring(PREFIX.length()), content));
        var failed = parts.entrySet().stream()
                .filter(e -> !(e.getValue() instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("ok"))))
                .map(Map.Entry::getKey)
                .toList();
        boolean ok = !parts.isEmpty() && failed.isEmpty();
        ctx.publish("implementation", ordered(
                "status", ok ? "ok" : "failed",
                "streams", List.copyOf(parts.keySet()),
                "failedStreams", failed,
                "parts", parts));
        if (!ok) {
            throw new IllegalStateException("implementation streams not all verified: " + failed);
        }
    }
}
