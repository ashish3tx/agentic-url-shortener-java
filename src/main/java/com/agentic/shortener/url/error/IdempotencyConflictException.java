package com.agentic.shortener.url.error;

public final class IdempotencyConflictException extends ShortenerException {

    public IdempotencyConflictException() {
        super("Idempotency-Key was already used with a different request payload");
    }
}
