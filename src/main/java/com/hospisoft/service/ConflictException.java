package com.hospisoft.service;

public class ConflictException extends RuntimeException {

    public static final String MESSAGE =
        "El registro cambió desde que lo abrió. Recargue e intente de nuevo.";

    private final String userMessage;

    public ConflictException(String detail) {
        this(detail, MESSAGE);
    }

    public ConflictException(String detail, String userMessage) {
        super(detail);
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
