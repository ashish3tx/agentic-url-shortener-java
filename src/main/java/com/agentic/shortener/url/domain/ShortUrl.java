package com.agentic.shortener.url.domain;

import java.time.Instant;

/**
 * Immutable link record; every change produces a new instance so repository updates stay atomic.
 */
public record ShortUrl(
        String code,
        String destination,
        Instant createdAt,
        Instant expiresAt,
        boolean customAlias,
        boolean active,
        String createdBy,
        Instant updatedAt) {

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public ShortUrl withDestination(String newDestination, Instant now) {
        return new ShortUrl(code, newDestination, createdAt, expiresAt, customAlias, active, createdBy, now);
    }

    public ShortUrl withExpiry(Instant newExpiresAt, Instant now) {
        return new ShortUrl(code, destination, createdAt, newExpiresAt, customAlias, active, createdBy, now);
    }

    public ShortUrl deactivated(Instant now) {
        return new ShortUrl(code, destination, createdAt, expiresAt, customAlias, false, createdBy, now);
    }
}
