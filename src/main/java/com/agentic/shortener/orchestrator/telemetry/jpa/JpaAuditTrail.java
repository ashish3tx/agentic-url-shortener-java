package com.agentic.shortener.orchestrator.telemetry.jpa;

import com.agentic.shortener.orchestrator.telemetry.AuditTrail;
import com.agentic.shortener.orchestrator.telemetry.TraceEvent;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

@Repository
class JpaAuditTrail implements AuditTrail {

    private static final int MAX_DETAIL_LENGTH = 4000;
    private static final TypeReference<Map<String, Object>> DETAIL_TYPE = new TypeReference<>() {
    };

    private final AuditEventJpaRepository events;
    private final JsonMapper json;

    JpaAuditTrail(AuditEventJpaRepository events, JsonMapper json) {
        this.events = events;
        this.json = json;
    }

    @Override
    public void append(TraceEvent event) {
        events.save(new AuditEventEntity(event.runId(), event.stageId(), event.event(), serialize(event.detail()), event.at()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TraceEvent> forRun(String runId) {
        return events.findByRunIdOrderByIdAsc(runId).stream()
                .map(e -> new TraceEvent(e.id(), e.occurredAt(), e.runId(), e.stageId(), e.event(), deserialize(e.detail())))
                .toList();
    }

    private String serialize(Map<String, Object> detail) {
        var text = json.writeValueAsString(detail);
        if (text.length() <= MAX_DETAIL_LENGTH) {
            return text;
        }
        // Keep the row valid JSON even when an oversized detail has to be cut.
        return json.writeValueAsString(Map.of("truncated", text.substring(0, MAX_DETAIL_LENGTH - 100)));
    }

    private Map<String, Object> deserialize(String detail) {
        return json.readValue(detail, DETAIL_TYPE);
    }
}
