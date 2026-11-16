package com.agentic.shortener.url.service;

import com.agentic.shortener.config.ShortenerProperties;
import com.agentic.shortener.url.ratelimit.SlidingWindowRateLimiter;
import com.agentic.shortener.url.repository.IdempotencyStore;
import com.agentic.shortener.url.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Enforces the click-retention control and keeps in-memory structures bounded.
 */
@Component
public class HousekeepingJobs {

    private static final Logger log = LoggerFactory.getLogger(HousekeepingJobs.class);

    private final UrlRepository repository;
    private final IdempotencyStore idempotency;
    private final SlidingWindowRateLimiter rateLimiter;
    private final ShortenerProperties properties;
    private final Clock clock;

    public HousekeepingJobs(UrlRepository repository, IdempotencyStore idempotency,
                            SlidingWindowRateLimiter rateLimiter, ShortenerProperties properties, Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void enforceRetention() {
        var now = clock.instant();
        int clicks = repository.purgeClicksBefore(now.minus(properties.analytics().retention()));
        int keys = idempotency.purgeBefore(now.minus(properties.idempotencyTtl()));
        if (clicks > 0 || keys > 0) {
            log.info("retention purge removed {} click events and {} idempotency keys", clicks, keys);
        }
    }

    @Scheduled(fixedDelayString = "PT1M")
    public void evictIdleRateLimitWindows() {
        rateLimiter.evictIdle();
    }
}
