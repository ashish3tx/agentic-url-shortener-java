package com.agentic.shortener.url.web;

import jakarta.validation.constraints.AssertTrue;

public record UpdateUrlRequest(String url, Long ttlSeconds) {

    @AssertTrue(message = "at least one of url or ttlSeconds is required")
    boolean isNotEmpty() {
        return url != null || ttlSeconds != null;
    }
}
