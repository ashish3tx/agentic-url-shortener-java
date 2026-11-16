package com.agentic.shortener.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("orchestrator")
public record OrchestratorProperties(
        @DefaultValue Governance governance,
        @DefaultValue("24h") Duration runRetention,
        @DefaultValue("500") @Min(1) int maxRetainedRuns,
        @DefaultValue("20s") Duration shutdownGracePeriod) {

    public record Governance(@DefaultValue("false") boolean autoApproveAllowed, @DefaultValue("true") boolean fourEyes) {
    }
}
