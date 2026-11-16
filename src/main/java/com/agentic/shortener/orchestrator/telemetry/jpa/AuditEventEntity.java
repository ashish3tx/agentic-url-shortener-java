package com.agentic.shortener.orchestrator.telemetry.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "orchestrator_audit_event")
class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String runId;
    private String stageId;
    private String event;
    private String detail;
    private Instant occurredAt;

    protected AuditEventEntity() {
    }

    AuditEventEntity(String runId, String stageId, String event, String detail, Instant occurredAt) {
        this.runId = runId;
        this.stageId = stageId;
        this.event = event;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    Long id() {
        return id;
    }

    String runId() {
        return runId;
    }

    String stageId() {
        return stageId;
    }

    String event() {
        return event;
    }

    String detail() {
        return detail;
    }

    Instant occurredAt() {
        return occurredAt;
    }
}
