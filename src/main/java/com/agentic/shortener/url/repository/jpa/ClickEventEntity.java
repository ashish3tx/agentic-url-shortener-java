package com.agentic.shortener.url.repository.jpa;

import com.agentic.shortener.url.domain.ClickEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Entity
@Table(name = "click_event")
class ClickEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private Instant clickedAt;
    private LocalDate clickDay;
    private String visitorHash;
    private String userAgent;
    private String referrer;

    protected ClickEventEntity() {
    }

    static ClickEventEntity from(ClickEvent event) {
        var entity = new ClickEventEntity();
        entity.code = event.code();
        entity.clickedAt = event.at();
        entity.clickDay = LocalDate.ofInstant(event.at(), ZoneOffset.UTC);
        entity.visitorHash = event.visitorHash();
        entity.userAgent = event.userAgent();
        entity.referrer = event.referrer();
        return entity;
    }
}
