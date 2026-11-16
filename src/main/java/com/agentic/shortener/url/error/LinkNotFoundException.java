package com.agentic.shortener.url.error;

public final class LinkNotFoundException extends ShortenerException {

    public LinkNotFoundException(String code) {
        super("short link '" + code + "' not found");
    }
}
