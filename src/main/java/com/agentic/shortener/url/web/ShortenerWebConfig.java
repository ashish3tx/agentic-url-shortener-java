package com.agentic.shortener.url.web;

import com.agentic.shortener.url.ratelimit.SlidingWindowRateLimiter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class ShortenerWebConfig implements WebMvcConfigurer {

    private final SlidingWindowRateLimiter limiter;

    public ShortenerWebConfig(SlidingWindowRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new WriteRateLimitInterceptor(limiter)).addPathPatterns("/api/v1/urls", "/api/v1/urls/**");
    }
}
