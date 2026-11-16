package com.agentic.shortener.url.repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<Entry> find(String scopedKey) {
        return Optional.ofNullable(entries.get(scopedKey));
    }

    @Override
    public boolean reserve(String scopedKey, String payloadFingerprint, Instant now) {
        return entries.putIfAbsent(scopedKey, new Entry(null, payloadFingerprint, now)) == null;
    }

    @Override
    public void complete(String scopedKey, String code) {
        entries.computeIfPresent(scopedKey, (key, entry) -> new Entry(code, entry.payloadFingerprint(), entry.createdAt()));
    }

    @Override
    public void release(String scopedKey) {
        entries.remove(scopedKey);
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        int before = entries.size();
        entries.values().removeIf(entry -> entry.createdAt().isBefore(cutoff));
        return before - entries.size();
    }
}
