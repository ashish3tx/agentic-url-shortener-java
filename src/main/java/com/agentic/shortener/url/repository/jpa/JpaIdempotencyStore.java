package com.agentic.shortener.url.repository.jpa;

import com.agentic.shortener.url.repository.IdempotencyStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
class JpaIdempotencyStore implements IdempotencyStore {

    private final IdempotencyKeyJpaRepository keys;

    JpaIdempotencyStore(IdempotencyKeyJpaRepository keys) {
        this.keys = keys;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Entry> find(String scopedKey) {
        return keys.findById(scopedKey).map(IdempotencyKeyEntity::toEntry);
    }

    // Committed in its own transaction so concurrent requests see the reservation immediately.
    @Override
    public boolean reserve(String scopedKey, String payloadFingerprint, Instant now) {
        try {
            keys.saveAndFlush(IdempotencyKeyEntity.reserve(scopedKey, payloadFingerprint, now));
            return true;
        } catch (DataIntegrityViolationException alreadyReserved) {
            return false;
        }
    }

    @Override
    @Transactional
    public void complete(String scopedKey, String code) {
        keys.findById(scopedKey).ifPresent(entity -> entity.complete(code));
    }

    @Override
    @Transactional
    public void release(String scopedKey) {
        keys.deleteById(scopedKey);
    }

    @Override
    @Transactional
    public int purgeBefore(Instant cutoff) {
        return keys.deleteOlderThan(cutoff);
    }
}
