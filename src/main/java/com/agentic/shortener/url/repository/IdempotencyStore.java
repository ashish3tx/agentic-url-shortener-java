package com.agentic.shortener.url.repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Reservation-based idempotency: a key is reserved before the link is created and completed
 * afterwards, so concurrent retries with the same key create exactly one link.
 */
public interface IdempotencyStore {

    /** {@code code} is null while the original request is still in flight. */
    record Entry(String code, String payloadFingerprint, Instant createdAt) {
    }

    Optional<Entry> find(String scopedKey);

    /** False when the key already exists (another request owns it). */
    boolean reserve(String scopedKey, String payloadFingerprint, Instant now);

    void complete(String scopedKey, String code);

    /** Frees a reservation whose request failed, so the client can retry with the same key. */
    void release(String scopedKey);

    int purgeBefore(Instant cutoff);
}
