package com.agentic.shortener.url.repository;

import com.agentic.shortener.url.domain.AnalyticsSummary;
import com.agentic.shortener.url.domain.ClickEvent;
import com.agentic.shortener.url.domain.ShortUrl;

import java.time.Instant;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Persistence port. Production uses the JPA adapter; the in-memory adapter backs the agents'
 * isolated contract-test sandbox and unit tests.
 */
public interface UrlRepository {

    Optional<ShortUrl> findByCode(String code);

    /** Atomic insert; false when the code is taken. This is what makes alias uniqueness race-free. */
    boolean saveIfAbsent(ShortUrl link);

    /** Atomic read-modify-write of one link. */
    Optional<ShortUrl> update(String code, UnaryOperator<ShortUrl> change);

    void recordClick(ClickEvent event);

    /** Aggregated in the store, so analytics never load the raw click log into memory. */
    AnalyticsSummary analytics(String code, int topReferrers);

    int purgeClicksBefore(Instant cutoff);

    long linkCount();
}
