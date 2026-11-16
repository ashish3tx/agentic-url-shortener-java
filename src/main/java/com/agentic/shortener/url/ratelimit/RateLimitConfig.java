package com.agentic.shortener.url.ratelimit;

import com.agentic.shortener.config.ShortenerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    @Bean
    SlidingWindowRateLimiter writeRateLimiter(ShortenerProperties properties) {
        return new SlidingWindowRateLimiter(properties.rateLimit().requests(), properties.rateLimit().window());
    }
}
