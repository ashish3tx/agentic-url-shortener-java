package com.agentic.shortener.url;

import com.agentic.shortener.url.domain.Caller;
import com.agentic.shortener.url.domain.CreateLinkCommand;
import com.agentic.shortener.url.error.AliasConflictException;
import com.agentic.shortener.url.error.IdempotencyInProgressException;
import com.agentic.shortener.url.repository.UrlRepository;
import com.agentic.shortener.url.service.ShortenerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The concurrency and aggregation guarantees, verified against the real JPA adapters and database. */
@SpringBootTest
class PersistenceIntegrationTest {

    @Autowired
    private ShortenerService service;
    @Autowired
    private UrlRepository repository;

    @Test
    void databaseEnforcesAliasUniquenessUnderConcurrency() {
        var alias = "race-" + suffix();
        Set<String> winners = ConcurrentHashMap.newKeySet();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 20).forEach(i -> pool.submit(() -> {
                try {
                    service.create(new CreateLinkCommand("https://example.com/" + i, alias, null, "user-" + i, null));
                    winners.add("user-" + i);
                } catch (AliasConflictException expected) {
                    // every loser gets a clean 409
                }
            }));
        }
        assertThat(winners).hasSize(1);
    }

    @Test
    void concurrentIdempotentRetriesCreateOneRow() {
        var owner = "client-" + suffix();
        var command = new CreateLinkCommand("https://example.com/once", null, null, owner, "key-1");
        Set<String> codes = ConcurrentHashMap.newKeySet();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 20).forEach(i -> pool.submit(() -> {
                try {
                    codes.add(service.create(command).link().code());
                } catch (IdempotencyInProgressException expected) {
                    // concurrent duplicate told to retry
                }
            }));
        }
        assertThat(codes).hasSize(1);
        assertThat(service.create(command).created()).isFalse();
    }

    @Test
    void analyticsAreAggregatedInTheDatabase() {
        var owner = new Caller("owner-" + suffix(), false);
        var code = service.create(new CreateLinkCommand("https://example.com", null, null, owner.id(), null)).link().code();
        service.resolve(code, "203.0.113.1", "ua", "https://a.example");
        service.resolve(code, "203.0.113.1", "ua", "https://a.example");
        service.resolve(code, "203.0.113.2", "ua", null);

        var summary = service.analytics(code, owner);

        assertThat(summary.totalClicks()).isEqualTo(3);
        assertThat(summary.uniqueVisitors()).isEqualTo(2);
        assertThat(summary.clicksByDay()).containsEntry(LocalDate.now(ZoneOffset.UTC), 3L);
        assertThat(summary.topReferrers()).containsExactly(
                Map.entry("https://a.example", 2L), Map.entry("direct", 1L));
        assertThat(summary.lastClickedAt()).isNotNull();
    }

    @Test
    void retentionPurgeDeletesOldClickRows() {
        var owner = new Caller("owner-" + suffix(), false);
        var code = service.create(new CreateLinkCommand("https://example.com", null, null, owner.id(), null)).link().code();
        service.resolve(code, "203.0.113.1", "ua", null);

        assertThat(repository.purgeClicksBefore(Instant.now().plusSeconds(60))).isPositive();
        assertThat(service.analytics(code, owner).totalClicks()).isZero();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
