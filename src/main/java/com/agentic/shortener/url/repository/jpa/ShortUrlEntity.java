package com.agentic.shortener.url.repository.jpa;

import com.agentic.shortener.url.domain.ShortUrl;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

@Entity
@Table(name = "short_url")
class ShortUrlEntity implements Persistable<String> {

    @Id
    private String code;
    @Column(nullable = false, length = 2048)
    private String destination;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant expiresAt;
    private boolean customAlias;
    private boolean active;
    @Column(nullable = false)
    private String createdBy;
    private Instant updatedAt;

    // Assigned ids look "existing" to Spring Data; this forces INSERT so duplicates fail instead of merging.
    @Transient
    private boolean isNew;

    protected ShortUrlEntity() {
    }

    static ShortUrlEntity insert(ShortUrl link) {
        var entity = new ShortUrlEntity();
        entity.code = link.code();
        entity.createdAt = link.createdAt();
        entity.customAlias = link.customAlias();
        entity.createdBy = link.createdBy();
        entity.apply(link);
        entity.isNew = true;
        return entity;
    }

    void apply(ShortUrl link) {
        destination = link.destination();
        expiresAt = link.expiresAt();
        active = link.active();
        updatedAt = link.updatedAt();
    }

    ShortUrl toDomain() {
        return new ShortUrl(code, destination, createdAt, expiresAt, customAlias, active, createdBy, updatedAt);
    }

    @Override
    public String getId() {
        return code;
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
