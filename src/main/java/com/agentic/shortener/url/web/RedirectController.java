package com.agentic.shortener.url.web;

import com.agentic.shortener.url.service.ShortenerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@Tag(name = "Redirect")
public class RedirectController {

    private final ShortenerService service;

    public RedirectController(ShortenerService service) {
        this.service = service;
    }

    // The regex keeps this catch-all away from /swagger-ui.html, /favicon.ico and multi-segment paths.
    @GetMapping("/{code:[A-Za-z0-9_-]{3,32}}")
    @Operation(summary = "Redirect to the destination (302) and record a click")
    public ResponseEntity<Void> redirect(
            @PathVariable String code,
            @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent,
            @RequestHeader(name = HttpHeaders.REFERER, required = false) String referer,
            HttpServletRequest request) {
        var destination = service.resolve(code, request.getRemoteAddr(), userAgent, referer);
        // 302 + no-store: a cached 301 would bypass analytics and outlive deactivation.
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(destination))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
