package com.agentic.shortener.security;

import com.agentic.shortener.url.domain.Caller;
import org.springframework.security.core.Authentication;

public final class Callers {

    private Callers() {
    }

    public static Caller from(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + Roles.ADMIN).equals(authority.getAuthority()));
        return new Caller(authentication.getName(), admin);
    }
}
