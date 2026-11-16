package com.agentic.shortener.url.error;

public final class LinkAccessDeniedException extends ShortenerException {

    public LinkAccessDeniedException(String code) {
        super("you do not own short link '" + code + "'");
    }
}
