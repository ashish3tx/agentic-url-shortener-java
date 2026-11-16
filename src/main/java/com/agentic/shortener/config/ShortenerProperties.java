package com.agentic.shortener.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Validated at startup so a misconfigured environment fails the deploy instead of misbehaving later. */
@Validated
@ConfigurationProperties("shortener")
public record ShortenerProperties(
        @DefaultValue("http://localhost:8080") @NotBlank String baseUrl,
        @DefaultValue("7") @Min(6) int codeLength,
        @DefaultValue @Valid RateLimit rateLimit,
        @DefaultValue @Valid Analytics analytics,
        @DefaultValue("24h") Duration idempotencyTtl) {

    public record RateLimit(@DefaultValue("30") @Min(1) int requests, @DefaultValue("60s") Duration window) {
    }

    public record Analytics(@DefaultValue("30d") Duration retention,
                            @NotBlank @Size(min = 16, message = "ipSalt must be at least 16 characters") String ipSalt) {
    }
}
