package com.agentic.shortener.url.domain;

import com.agentic.shortener.common.Hashing;

import java.util.Objects;

public record CreateLinkCommand(String url, String alias, Long ttlSeconds, String createdBy, String idempotencyKey) {

    public CreateLinkCommand {
        Objects.requireNonNull(createdBy, "createdBy");
    }

    public static CreateLinkCommand of(String url) {
        return new CreateLinkCommand(url, null, null, "system", null);
    }

    /**
     * What an idempotent replay must match; the key alone is not enough.
     */
    public String payloadFingerprint() {
        return String.join("|", String.valueOf(url), String.valueOf(alias), String.valueOf(ttlSeconds));
    }
}
