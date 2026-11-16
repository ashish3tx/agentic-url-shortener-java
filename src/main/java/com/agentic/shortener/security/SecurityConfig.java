package com.agentic.shortener.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import static com.agentic.shortener.security.Roles.APPROVER;
import static com.agentic.shortener.security.Roles.ENGINEER;
import static com.agentic.shortener.security.Roles.OPS;
import static com.agentic.shortener.security.Roles.USER;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AppSecurityProperties properties,
                                            ObjectProvider<JwtDecoder> jwtDecoder) throws Exception {
        boolean basicEnabled = !properties.users().isEmpty();
        boolean jwtEnabled = jwtDecoder.getIfAvailable() != null;
        if (!basicEnabled && !jwtEnabled) {
            throw new IllegalStateException("No authentication configured: set an OIDC issuer "
                    + "(spring.security.oauth2.resourceserver.jwt.issuer-uri) or app.security.users");
        }

        http
                // Stateless API authenticated per request (Basic/Bearer), no cookies, so CSRF does not apply.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Plain 401 without WWW-Authenticate so browsers never pop a native login dialog.
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.ico").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**",
                                "/v3/api-docs.yaml").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole(OPS)
                        .requestMatchers("/api/**").hasRole(USER)
                        .requestMatchers(HttpMethod.POST, "/orchestrator/runs/*/approve", "/orchestrator/runs/*/reject")
                        .hasRole(APPROVER)
                        .requestMatchers(HttpMethod.POST, "/orchestrator/**").hasRole(ENGINEER)
                        .requestMatchers(HttpMethod.GET, "/orchestrator/**").hasAnyRole(ENGINEER, APPROVER, OPS)
                        // Short-link redirects are public by design: single path segment only.
                        .requestMatchers(HttpMethod.GET, "/*").permitAll()
                        .anyRequest().denyAll());

        if (basicEnabled) {
            http.httpBasic(basic -> basic.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        }
        if (jwtEnabled) {
            http.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter(properties))));
        }
        return http.build();
    }

    // Always defined so Spring Boot never falls back to its generated default user.
    @Bean
    UserDetailsService userDetailsService(AppSecurityProperties properties) {
        var users = properties.users().stream()
                .map(user -> User.withUsername(user.username())
                        .password(user.password())
                        .roles(user.roles().toArray(String[]::new))
                        .build())
                .toList();
        return new InMemoryUserDetailsManager(users);
    }

    // Delegating encoder: passwords carry their scheme prefix, e.g. {bcrypt}$2a$...
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    private static JwtAuthenticationConverter jwtConverter(AppSecurityProperties properties) {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(properties.jwtRolesClaim());
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        converter.setPrincipalClaimName(properties.jwtPrincipalClaim());
        return converter;
    }
}
