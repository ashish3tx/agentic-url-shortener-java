package com.agentic.shortener.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    // Injected everywhere time matters so TTL, retention and MTTR are testable.
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("Agentic URL Shortener")
                .version("1.0.0")
                .description("Product APIs (URL shortener) and the agentic SDLC orchestrator control plane. "
                        + "Use Authorize: HTTP Basic locally, Bearer JWT in production."))
                .components(new Components()
                        .addSecuritySchemes("basicAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic"))
                        .addSecuritySchemes("bearerAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
