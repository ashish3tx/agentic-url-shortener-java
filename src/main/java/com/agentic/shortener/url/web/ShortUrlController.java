package com.agentic.shortener.url.web;

import com.agentic.shortener.config.ShortenerProperties;
import com.agentic.shortener.url.domain.AnalyticsSummary;
import com.agentic.shortener.url.domain.CreateLinkCommand;
import com.agentic.shortener.security.Callers;
import com.agentic.shortener.url.service.ShortenerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/urls")
@Tag(name = "Short links", description = "Requires role USER; links are only visible to their owner or an ADMIN")
public class ShortUrlController {

    private final ShortenerService service;
    private final String baseUrl;

    public ShortUrlController(ShortenerService service, ShortenerProperties properties) {
        this.service = service;
        this.baseUrl = properties.baseUrl();
    }

    @PostMapping
    @Operation(summary = "Create a short link", description = "201 on create, 200 when an Idempotency-Key replays")
    public ResponseEntity<ShortUrlResponse> create(
            @Valid @RequestBody CreateUrlRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication caller) {
        var result = service.create(new CreateLinkCommand(request.url(), request.alias(), request.ttlSeconds(),
                caller.getName(), idempotencyKey));
        var body = ShortUrlResponse.from(result.link(), baseUrl);
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/urls/" + body.code())).body(body)
                : ResponseEntity.ok(body);
    }

    @GetMapping("/{code}")
    @Operation(summary = "Get link metadata")
    public ShortUrlResponse get(@PathVariable String code, Authentication caller) {
        return ShortUrlResponse.from(service.get(code, Callers.from(caller)), baseUrl);
    }

    @GetMapping("/{code}/analytics")
    @Operation(summary = "Click analytics: totals, unique visitors, by day, top referrers")
    public AnalyticsSummary analytics(@PathVariable String code, Authentication caller) {
        return service.analytics(code, Callers.from(caller));
    }

    @PatchMapping("/{code}")
    @Operation(summary = "Change destination and/or TTL")
    public ShortUrlResponse update(@PathVariable String code, @Valid @RequestBody UpdateUrlRequest request,
                                   Authentication caller) {
        return ShortUrlResponse.from(service.update(code, request.url(), request.ttlSeconds(), Callers.from(caller)), baseUrl);
    }

    @DeleteMapping("/{code}")
    @Operation(summary = "Deactivate a link (later redirects return 410)")
    public ResponseEntity<Void> deactivate(@PathVariable String code, Authentication caller) {
        service.deactivate(code, Callers.from(caller));
        return ResponseEntity.noContent().build();
    }
}
