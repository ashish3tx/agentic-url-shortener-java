package com.agentic.shortener.url.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record CreateUrlRequest(
        @NotBlank @Schema(example = "https://example.com/some/long/path") String url,
        @Schema(description = "Optional custom alias, 3-32 chars of [A-Za-z0-9_-]", example = "launch") String alias,
        @Schema(description = "Optional time-to-live in seconds (60..31536000)", example = "86400") Long ttlSeconds) {
}
