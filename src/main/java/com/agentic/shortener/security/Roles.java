package com.agentic.shortener.security;

/** Role names (without the ROLE_ prefix). */
public final class Roles {

    /** May create and manage their own short links. */
    public static final String USER = "USER";
    /** May manage any short link. */
    public static final String ADMIN = "ADMIN";
    /** May start, amend and stop orchestrator runs. */
    public static final String ENGINEER = "ENGINEER";
    /** May approve or reject human checkpoints. */
    public static final String APPROVER = "APPROVER";
    /** May read metrics and actuator endpoints. */
    public static final String OPS = "OPS";

    private Roles() {
    }
}
