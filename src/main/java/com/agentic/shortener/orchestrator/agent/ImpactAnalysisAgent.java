package com.agentic.shortener.orchestrator.agent;

import com.agentic.shortener.orchestrator.model.StageContext;
import com.agentic.shortener.url.ratelimit.SlidingWindowRateLimiter;
import com.agentic.shortener.url.repository.UrlRepository;
import com.agentic.shortener.url.service.LinkValidator;
import com.agentic.shortener.url.service.ShortenerService;
import com.agentic.shortener.url.web.ShortUrlController;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.agentic.shortener.orchestrator.agent.Artifacts.ordered;

/**
 * Brownfield codebase reasoning. Instead of a hard-coded list, it inspects the running
 * application: the live HTTP route table and the public surface of the classes in the change path.
 */
@Component
public class ImpactAnalysisAgent implements Agent {

    private static final String PRODUCT_PACKAGE = "com.agentic.shortener.url";

    private final RequestMappingHandlerMapping handlerMapping;

    public ImpactAnalysisAgent(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    @Override
    public String id() {
        return "impact_analysis";
    }

    @Override
    public void execute(StageContext ctx) {
        var endpoints = liveProductEndpoints();
        if (endpoints.isEmpty()) {
            throw new IllegalStateException("no product endpoints discovered; cannot assess impact");
        }
        var components = new LinkedHashMap<String, Object>();
        components.put(ShortUrlController.class.getSimpleName(), surface(ShortUrlController.class, "PATCH handler, 201/200 create semantics"));
        components.put(ShortenerService.class.getSimpleName(), surface(ShortenerService.class, "update(), TTL expiry, alias allocation"));
        components.put(LinkValidator.class.getSimpleName(), surface(LinkValidator.class, "alias charset, reserved names, TTL bounds"));
        components.put(SlidingWindowRateLimiter.class.getSimpleName(), surface(SlidingWindowRateLimiter.class, "new: per-identity sliding window"));
        components.put(UrlRepository.class.getSimpleName(), surface(UrlRepository.class, "atomic update via computeIfPresent"));

        ctx.publish("impact", ordered(
                "liveEndpoints", endpoints,
                "impactedComponents", components,
                "dataFlow", "POST/PATCH/DELETE -> rate limiter -> validation -> service -> repository; GET /{code} -> resolve -> click event",
                "apiDelta", List.of("POST body: alias, ttlSeconds", "PATCH /api/v1/urls/{code}", "429 + Retry-After on writes", "410 for expired links"),
                "regressionRisks", List.of(
                        "GET /{code} must remain 302 with no-store",
                        "existing codes must stay resolvable",
                        "alias must not shadow /api, /actuator, /orchestrator, /swagger-ui"),
                "untouched", List.of("redirect hot path signature", "analytics response shape")));
    }

    private List<String> liveProductEndpoints() {
        return handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().getBeanType().getPackageName().startsWith(PRODUCT_PACKAGE))
                .map(e -> e.getKey().getMethodsCondition().getMethods() + " " + e.getKey().getPatternValues()
                        + " -> " + e.getValue().getBeanType().getSimpleName() + "#" + e.getValue().getMethod().getName())
                .sorted()
                .toList();
    }

    private static Map<String, Object> surface(Class<?> type, String change) {
        var methods = Arrays.stream(type.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic())
                .map(Method::getName)
                .distinct()
                .sorted()
                .toList();
        return ordered("type", type.getName(), "publicMethods", methods, "change", change);
    }
}
