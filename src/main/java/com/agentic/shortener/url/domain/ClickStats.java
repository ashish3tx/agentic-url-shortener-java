package com.agentic.shortener.url.domain;

import java.time.LocalDate;

/** Projection rows for analytics aggregation. */
public final class ClickStats {

    private ClickStats() {
    }

    public record DayCount(LocalDate day, Long clicks) {
    }

    public record ReferrerCount(String referrer, Long clicks) {
    }
}
