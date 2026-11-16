package com.agentic.shortener.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Basic-auth users are for local/dev only (prod sets none and relies on OIDC JWTs).
 * JWT claim names are configurable because every identity provider names them differently.
 */
@Validated
@ConfigurationProperties("app.security")
public record AppSecurityProperties(
        @DefaultValue List<@Valid User> users,
        @DefaultValue("roles") String jwtRolesClaim,
        @DefaultValue("sub") String jwtPrincipalClaim) {

    public record User(@NotBlank String username, @NotBlank String password, @NotEmpty List<String> roles) {
    }
}
