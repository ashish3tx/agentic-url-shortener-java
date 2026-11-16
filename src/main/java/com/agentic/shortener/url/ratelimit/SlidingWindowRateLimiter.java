package com.agentic.shortener.url.ratelimit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Sliding-window log limiter per identity. All mutation happens inside ConcurrentHashMap.compute,
 * which serialises access per key, so no extra locking is needed and eviction cannot race a hit.
 * Single-node only: a multi-instance deployment would back this with Redis.
 */
public class SlidingWindowRateLimiter {

    public record Decision(boolean allowed, int remaining, long retryAfterSeconds) {
    }

    private final int limit;
    private final long windowNanos;
    private final LongSupplier nanoTime;
    private final Map<String, ArrayDeque<Long>> windows = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(int limit, Duration window) {
        this(limit, window, System::nanoTime);
    }

    SlidingWindowRateLimiter(int limit, Duration window, LongSupplier nanoTime) {
        if (limit <= 0 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("limit and window must be positive");
        }
        this.limit = limit;
        this.windowNanos = window.toNanos();
        this.nanoTime = nanoTime;
    }

    public Decision tryAcquire(String identity) {
        long now = nanoTime.getAsLong();
        var result = new Decision[1];
        windows.compute(identity, (key, hits) -> {
            var window = hits == null ? new ArrayDeque<Long>() : hits;
            evictExpired(window, now);
            if (window.size() >= limit) {
                long waitNanos = window.peekFirst() + windowNanos - now;
                result[0] = new Decision(false, 0, Math.max(1, (long) Math.ceil(waitNanos / 1e9)));
            } else {
                window.addLast(now);
                result[0] = new Decision(true, limit - window.size(), 0);
            }
            return window;
        });
        return result[0];
    }

    /**
     * Drops identities with no hits in the current window so the map does not grow forever.
     */
    public void evictIdle() {
        long now = nanoTime.getAsLong();
        for (var identity : windows.keySet()) {
            windows.computeIfPresent(identity, (key, window) -> {
                evictExpired(window, now);
                return window.isEmpty() ? null : window;
            });
        }
    }

    int trackedIdentities() {
        return windows.size();
    }

    private void evictExpired(ArrayDeque<Long> window, long now) {
        while (!window.isEmpty() && now - window.peekFirst() >= windowNanos) {
            window.pollFirst();
        }
    }
}
