package com.hospisoft.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ValidationException extends RuntimeException {

    public static final String MESSAGE = "Datos inválidos";

    private final Map<String, String> errors;

    public ValidationException(Map<String, String> errors) {
        super(MESSAGE);
        this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }

    public Map<String, String> errors() {
        return errors;
    }
}
