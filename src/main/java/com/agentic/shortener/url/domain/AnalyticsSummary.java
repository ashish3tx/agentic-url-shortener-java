package com.agentic.shortener.url.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

public record AnalyticsSummary(
        String code,
        long totalClicks,
        long uniqueVisitors,
        Instant lastClickedAt,
        Map<LocalDate, Long> clicksByDay,
        Map<String, Long> topReferrers) {
}
