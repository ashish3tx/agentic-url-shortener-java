package com.agentic.shortener.url.domain;

/** The authenticated party acting on a link. Admins may manage any link; everyone else only their own. */
public record Caller(String id, boolean admin) {

    public static Caller system() {
        return new Caller("system", true);
    }

    public boolean canManage(ShortUrl link) {
        return admin || link.createdBy().equals(id);
    }
}
