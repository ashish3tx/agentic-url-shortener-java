package com.agentic.shortener.url.domain;

import java.time.Instant;

/**
 * A single redirect. The client IP is stored only as a salted, day-rotated hash.
 */
public record ClickEvent(String code, Instant at, String visitorHash, String userAgent, String referrer) {
}
