package com.agentic.shortener.orchestrator.telemetry.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface AuditEventJpaRepository extends JpaRepository<AuditEventEntity, Long> {

    List<AuditEventEntity> findByRunIdOrderByIdAsc(String runId);
}
