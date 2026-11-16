package com.agentic.shortener.url.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3, Duration.ofSeconds(60), nanos::get);

    @Test
    void allowsUpToLimitThenRejectsWithRetryAfter() {
        assertThat(limiter.tryAcquire("a").remaining()).isEqualTo(2);
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");

        var rejected = limiter.tryAcquire("a");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void windowSlidesAndIdentitiesAreIsolated() {
        IntStream.range(0, 3).forEach(i -> limiter.tryAcquire("a"));
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();

        nanos.addAndGet(Duration.ofSeconds(60).toNanos());
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    void evictsIdleIdentities() {
        limiter.tryAcquire("a");
        nanos.addAndGet(Duration.ofMinutes(2).toNanos());
        limiter.evictIdle();
        assertThat(limiter.trackedIdentities()).isZero();
    }

    @Test
    void neverAdmitsMoreThanLimitUnderConcurrency() throws Exception {
        var shared = new SlidingWindowRateLimiter(50, Duration.ofMinutes(1));
        var admitted = new AtomicInteger();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 500).forEach(i -> pool.submit(() -> {
                if (shared.tryAcquire("hot").allowed()) {
                    admitted.incrementAndGet();
                }
            }));
        }
        assertThat(admitted).hasValue(50);
    }
}
