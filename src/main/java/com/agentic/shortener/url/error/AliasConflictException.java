package com.agentic.shortener.url.error;

public final class AliasConflictException extends ShortenerException {

    public AliasConflictException(String alias) {
        super("alias '" + alias + "' already exists");
    }
}
