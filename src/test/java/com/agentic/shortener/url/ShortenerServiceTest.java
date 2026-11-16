package com.agentic.shortener.url;

import com.agentic.shortener.config.ShortenerProperties;
import com.agentic.shortener.support.MutableClock;
import com.agentic.shortener.url.domain.Caller;
import com.agentic.shortener.url.domain.CreateLinkCommand;
import com.agentic.shortener.url.error.AliasConflictException;
import com.agentic.shortener.url.error.IdempotencyConflictException;
import com.agentic.shortener.url.error.IdempotencyInProgressException;
import com.agentic.shortener.url.error.LinkAccessDeniedException;
import com.agentic.shortener.url.error.InvalidLinkException;
import com.agentic.shortener.url.error.LinkGoneException;
import com.agentic.shortener.url.error.LinkNotFoundException;
import com.agentic.shortener.url.repository.InMemoryIdempotencyStore;
import com.agentic.shortener.url.repository.InMemoryUrlRepository;
import com.agentic.shortener.url.service.CodeGenerator;
import com.agentic.shortener.url.service.LinkValidator;
import com.agentic.shortener.url.service.ShortenerService;
import com.agentic.shortener.url.service.VisitorHasher;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShortenerServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T10:00:00Z"));
    private final ShortenerProperties properties = new ShortenerProperties("http://sho.rt", 7,
            new ShortenerProperties.RateLimit(30, Duration.ofMinutes(1)),
            new ShortenerProperties.Analytics(Duration.ofDays(30), "test-salt-0123456789"), Duration.ofHours(24));
    private final InMemoryUrlRepository repository = new InMemoryUrlRepository();
    private final InMemoryIdempotencyStore idempotency = new InMemoryIdempotencyStore();
    private final ShortenerService service = new ShortenerService(repository, idempotency, new LinkValidator(),
            new CodeGenerator(properties), new VisitorHasher(properties, clock), clock);

    @Test
    void createsRandomCodeAndResolvesWithClick() {
        var result = service.create(CreateLinkCommand.of("https://example.com/a"));

        assertThat(result.created()).isTrue();
        assertThat(result.link().code()).hasSize(7).matches("[0-9A-Za-z]+");
        assertThat(service.resolve(result.link().code(), "203.0.113.1", "ua", null)).isEqualTo("https://example.com/a");
        assertThat(service.analytics(result.link().code(), Caller.system()).totalClicks()).isEqualTo(1);
    }

    @Test
    void customAliasIsUnique() {
        service.create(new CreateLinkCommand("https://example.com", "launch", null, "a", null));

        assertThatThrownBy(() -> service.create(new CreateLinkCommand("https://example.org", "launch", null, "b", null)))
                .isInstanceOf(AliasConflictException.class);
    }

    @Test
    void concurrentAliasClaimsProduceExactlyOneWinner() {
        Set<String> winners = ConcurrentHashMap.newKeySet();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 50).forEach(i -> pool.submit(() -> {
                try {
                    service.create(new CreateLinkCommand("https://example.com/" + i, "race", null, "c" + i, null));
                    winners.add("c" + i);
                } catch (AliasConflictException ignored) {
                    // expected for every loser
                }
            }));
        }
        assertThat(winners).hasSize(1);
    }

    @Test
    void idempotentReplayReturnsSameLinkAndRejectsDifferentPayload() {
        var command = new CreateLinkCommand("https://example.com/x", null, null, "client-1", "key-1");
        var first = service.create(command);
        var replay = service.create(command);

        assertThat(replay.created()).isFalse();
        assertThat(replay.link().code()).isEqualTo(first.link().code());
        assertThatThrownBy(() -> service.create(new CreateLinkCommand("https://example.com/y", null, null, "client-1", "key-1")))
                .isInstanceOf(IdempotencyConflictException.class);
        // Same key from another caller is a different scope.
        assertThat(service.create(new CreateLinkCommand("https://example.com/y", null, null, "client-2", "key-1")).created()).isTrue();
    }

    @Test
    void expiredAndDeactivatedLinksAreGone() {
        var expiring = service.create(new CreateLinkCommand("https://example.com", null, 60L, "a", null)).link();
        var retired = service.create(CreateLinkCommand.of("https://example.org")).link();
        service.deactivate(retired.code(), Caller.system());

        clock.advance(Duration.ofSeconds(61));

        assertThatThrownBy(() -> service.resolve(expiring.code(), "ip", "ua", null)).isInstanceOf(LinkGoneException.class).hasMessageContaining("expired");
        assertThatThrownBy(() -> service.resolve(retired.code(), "ip", "ua", null)).isInstanceOf(LinkGoneException.class).hasMessageContaining("deactivated");
        assertThatThrownBy(() -> service.resolve("missing", "ip", "ua", null)).isInstanceOf(LinkNotFoundException.class);
    }

    @Test
    void updateChangesDestinationAndTtlButRevalidates() {
        var link = service.create(CreateLinkCommand.of("https://example.com")).link();

        var updated = service.update(link.code(), "https://example.org/new", 3600L, Caller.system());

        assertThat(updated.destination()).isEqualTo("https://example.org/new");
        assertThat(updated.expiresAt()).isEqualTo(clock.instant().plusSeconds(3600));
        assertThatThrownBy(() -> service.update(link.code(), "http://127.0.0.1", null, Caller.system())).isInstanceOf(InvalidLinkException.class);
    }

    @Test
    void analyticsCountsUniqueVisitorsWithoutStoringRawIps() {
        var code = service.create(CreateLinkCommand.of("https://example.com")).link().code();
        service.resolve(code, "203.0.113.1", "ua", "https://news.example");
        service.resolve(code, "203.0.113.1", "ua", "https://news.example");
        service.resolve(code, "203.0.113.2", "ua", null);

        var summary = service.analytics(code, Caller.system());

        assertThat(summary.totalClicks()).isEqualTo(3);
        assertThat(summary.uniqueVisitors()).isEqualTo(2);
        assertThat(summary.topReferrers()).containsEntry("https://news.example", 2L).containsEntry("direct", 1L);
        assertThat(repository.clicksFor(code)).allSatisfy(click -> assertThat(click.visitorHash()).doesNotContain("203.0.113"));
    }

    @Test
    void retentionPurgeRemovesOldClicks() {
        var code = service.create(CreateLinkCommand.of("https://example.com")).link().code();
        service.resolve(code, "ip", "ua", null);
        clock.advance(Duration.ofDays(31));

        assertThat(repository.purgeClicksBefore(clock.instant().minus(Duration.ofDays(30)))).isEqualTo(1);
        assertThat(service.analytics(code, Caller.system()).totalClicks()).isZero();
    }

    @Test
    void onlyOwnerOrAdminCanManageALink() {
        var link = service.create(new CreateLinkCommand("https://example.com", null, null, "alice", null)).link();

        assertThat(service.get(link.code(), new Caller("alice", false)).code()).isEqualTo(link.code());
        assertThat(service.analytics(link.code(), new Caller("root", true)).totalClicks()).isZero();
        assertThatThrownBy(() -> service.deactivate(link.code(), new Caller("mallory", false)))
                .isInstanceOf(LinkAccessDeniedException.class);
        assertThat(service.get(link.code(), Caller.system()).active()).isTrue();
    }

    @Test
    void idempotencyReservationInFlightIsReportedAndFailedRequestsReleaseTheKey() {
        var command = new CreateLinkCommand("https://example.com/x", null, null, "client-1", "key-2");
        idempotency.reserve("client-1:key-2", command.payloadFingerprint(), clock.instant());
        assertThatThrownBy(() -> service.create(command)).isInstanceOf(IdempotencyInProgressException.class);

        var invalid = new CreateLinkCommand("javascript:alert(1)", null, null, "client-1", "key-3");
        assertThatThrownBy(() -> service.create(invalid)).isInstanceOf(InvalidLinkException.class);
        assertThat(idempotency.find("client-1:key-3")).isEmpty();
    }

    @Test
    void concurrentRetriesWithSameKeyCreateExactlyOneLink() {
        var command = new CreateLinkCommand("https://example.com/once", null, null, "client-9", "same-key");
        Set<String> codes = ConcurrentHashMap.newKeySet();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 30).forEach(i -> pool.submit(() -> {
                try {
                    codes.add(service.create(command).link().code());
                } catch (IdempotencyInProgressException ignored) {
                    // a concurrent duplicate is told to retry, never given a second link
                }
            }));
        }
        assertThat(codes).hasSize(1);
        assertThat(repository.linkCount()).isEqualTo(1);
    }
}
