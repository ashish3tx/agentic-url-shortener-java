package com.agentic.shortener.url.error;

public final class InvalidLinkException extends ShortenerException {

    private final String field;

    public InvalidLinkException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
