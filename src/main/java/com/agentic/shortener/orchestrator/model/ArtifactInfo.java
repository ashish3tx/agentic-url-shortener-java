package com.agentic.shortener.orchestrator.model;

import java.time.Instant;

/**
 * Provenance of an artifact: which stage produced it, its content hash, and how many times it changed.
 */
public record ArtifactInfo(String name, String producedBy, String sha256, int version, Instant producedAt) {
}
