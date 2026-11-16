package com.agentic.shortener.url.error;

/**
 * Closed set of domain failures; the API layer maps each one to exactly one HTTP status.
 */
public sealed abstract class ShortenerException extends RuntimeException
        permits InvalidLinkException, LinkNotFoundException, LinkGoneException,
        AliasConflictException, IdempotencyConflictException, IdempotencyInProgressException,
                RateLimitExceededException, LinkAccessDeniedException {

    protected ShortenerException(String message) {
        super(message);
    }
}
