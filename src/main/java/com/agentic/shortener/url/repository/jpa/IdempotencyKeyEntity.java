package com.agentic.shortener.url.repository.jpa;

import com.agentic.shortener.url.repository.IdempotencyStore;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

@Entity
@Table(name = "idempotency_key")
class IdempotencyKeyEntity implements Persistable<String> {

    @Id
    private String scopedKey;
    private String payloadFingerprint;
    private String code;
    private Instant createdAt;

    @Transient
    private boolean isNew;

    protected IdempotencyKeyEntity() {
    }

    static IdempotencyKeyEntity reserve(String scopedKey, String payloadFingerprint, Instant now) {
        var entity = new IdempotencyKeyEntity();
        entity.scopedKey = scopedKey;
        entity.payloadFingerprint = payloadFingerprint;
        entity.createdAt = now;
        entity.isNew = true;
        return entity;
    }

    void complete(String linkCode) {
        code = linkCode;
    }

    IdempotencyStore.Entry toEntry() {
        return new IdempotencyStore.Entry(code, payloadFingerprint, createdAt);
    }

    @Override
    public String getId() {
        return scopedKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        isNew = false;
    }
}
