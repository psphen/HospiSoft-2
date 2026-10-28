package com.hospisoft.service;

/**
 * Unchecked: an optimistic-concurrency or state check failed (the row changed since the client
 * loaded it). Reported as code CONFLICT; the client must reload and retry.
 */
public class ConflictException extends RuntimeException {

    public static final String MESSAGE =
        "El registro cambió desde que lo abrió. Recargue e intente de nuevo.";

    private final String userMessage;

    public ConflictException(String detail) {
        this(detail, MESSAGE);
    }

    /** @param userMessage Spanish text shown to the user instead of the generic one */
    public ConflictException(String detail, String userMessage) {
        super(detail);
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
