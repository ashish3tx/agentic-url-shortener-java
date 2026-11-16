package com.agentic.shortener.url.domain;

/**
 * {@code created=false} means an Idempotency-Key replay returned the original link.
 */
public record CreateResult(ShortUrl link, boolean created) {
}
