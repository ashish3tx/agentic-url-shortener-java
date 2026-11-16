package com.agentic.shortener.url.web;

import com.agentic.shortener.common.ClientIdentity;
import com.agentic.shortener.url.error.RateLimitExceededException;
import com.agentic.shortener.url.ratelimit.SlidingWindowRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Limits write traffic only; redirects are the hot read path and must stay cheap.
 */
public class WriteRateLimitInterceptor implements HandlerInterceptor {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PATCH", "PUT", "DELETE");

    private final SlidingWindowRateLimiter limiter;

    public WriteRateLimitInterceptor(SlidingWindowRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!WRITE_METHODS.contains(request.getMethod())) {
            return true;
        }
        var decision = limiter.tryAcquire(ClientIdentity.resolve(request));
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        return true;
    }
}
