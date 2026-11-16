package com.agentic.shortener.url.service;

import com.agentic.shortener.common.Hashing;
import com.agentic.shortener.config.ShortenerProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Hashes client IPs with a secret salt that rotates daily. Unique-visitor counts still work
 * within a day, but stored hashes cannot be joined across days or reversed by brute force.
 */
@Component
public class VisitorHasher {

    private final String salt;
    private final Clock clock;

    public VisitorHasher(ShortenerProperties properties, Clock clock) {
        this.salt = properties.analytics().ipSalt();
        this.clock = clock;
    }

    public String hash(String ip) {
        var day = LocalDate.now(clock);
        return Hashing.sha256(salt + ":" + day + ":" + (ip == null ? "" : ip)).substring(0, 16);
    }
}
