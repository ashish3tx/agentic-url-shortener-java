package com.agentic.shortener.url.error;

public final class IdempotencyInProgressException extends ShortenerException {

    public IdempotencyInProgressException() {
        super("a request with this Idempotency-Key is still being processed; retry shortly");
    }
}
