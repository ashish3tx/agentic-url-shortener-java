package com.agentic.shortener.url.web;

import com.agentic.shortener.url.domain.ShortUrl;

import java.time.Instant;

/**
 * Public view of a link; deliberately omits the owner identity.
 */
public record ShortUrlResponse(
        String code,
        String shortUrl,
        String destination,
        Instant createdAt,
        Instant expiresAt,
        Instant updatedAt,
        boolean customAlias,
        boolean active) {

    static ShortUrlResponse from(ShortUrl link, String baseUrl) {
        return new ShortUrlResponse(link.code(), baseUrl + "/" + link.code(), link.destination(),
                link.createdAt(), link.expiresAt(), link.updatedAt(), link.customAlias(), link.active());
    }
}
