package com.agentic.shortener.url.service;

import com.agentic.shortener.config.ShortenerProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Random base62 codes: unguessable (no enumeration of other people's links) and 62^7 ≈ 3.5e12 space.
 */
@Component
public class CodeGenerator {

    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private final SecureRandom random = new SecureRandom();
    private final int length;

    public CodeGenerator(ShortenerProperties properties) {
        this.length = properties.codeLength();
    }

    public String next() {
        var code = new char[length];
        for (int i = 0; i < length; i++) {
            code[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }
}
