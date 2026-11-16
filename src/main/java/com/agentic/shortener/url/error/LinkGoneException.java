package com.agentic.shortener.url.error;

public final class LinkGoneException extends ShortenerException {

    public LinkGoneException(String code, String reason) {
        super("short link '" + code + "' " + reason);
    }
}
