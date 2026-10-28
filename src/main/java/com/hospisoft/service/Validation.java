package com.hospisoft.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Collects field errors (Spanish messages) and offers the common checks used by the services. */
public final class Validation {

    static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    public static final String REQUIRED = "Campo obligatorio";
    public static final String INVALID_EMAIL = "Correo electrónico inválido";
    public static final String DUPLICATE_DOCUMENTO = "Ya existe un registro con este documento";
    public static final String DUPLICATE_CODIGO = "Ya existe un registro con este código";
    public static final String NOT_AN_INTEGER = "Debe ser un número entero";
    public static final String NEGATIVE = "Debe ser mayor o igual a 0";
    public static final String TOO_LARGE = "Debe ser menor o igual a " + Integer.MAX_VALUE;
    public static final String RELOAD_RECORD = "Recargue el registro";
    public static final String NOT_EXISTS = "No existe";
    public static final String MUST_BE_ACTIVE = "Debe estar activo";

    /** Duplicate-key message for a unique field. */
    public static String duplicateMessage(String field) {
        return "codigo".equals(field) ? DUPLICATE_CODIGO : DUPLICATE_DOCUMENTO;
    }
    /** Longest free-text filter accepted by the list use cases. */
    public static final int MAX_FILTER_TEXT = 100;

    private final Map<String, String> errors = new LinkedHashMap<>();

    /** Trims; null for blank. */
    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String maxLengthMessage(int max) {
        return "Máximo " + max + " caracteres";
    }

    /** @throws ValidationException on field {@code texto} when the free-text filter is too long */
    static void checkFilterText(String texto) {
        if (texto != null && texto.length() > MAX_FILTER_TEXT) {
            throw new ValidationException(Map.of("texto", maxLengthMessage(MAX_FILTER_TEXT)));
        }
    }

    void required(String field, String value) {
        if (value == null) {
            reject(field, REQUIRED);
        }
    }

    void maxLength(String field, String value, int max) {
        if (value != null && value.length() > max && !errors.containsKey(field)) {
            reject(field, maxLengthMessage(max));
        }
    }

    /**
     * Like {@link #nonNegativeInt} but bounded: any value outside [{@code min}, {@code max}] -
     * negative ones included - reports {@code rangeMessage}. Returns {@code min} when invalid.
     */
    int intInRange(String field, Object raw, int min, int max, String rangeMessage) {
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            reject(field, REQUIRED);
            return min;
        }
        Validation probe = new Validation();
        int value = probe.nonNegativeInt(field, raw);
        String error = probe.errors().get(field);
        if (NOT_AN_INTEGER.equals(error)) {
            reject(field, NOT_AN_INTEGER);
            return min;
        }
        if (error != null || value < min || value > max) {
            // NEGATIVE and TOO_LARGE both mean "outside the accepted range" here
            reject(field, rangeMessage);
            return min;
        }
        return value;
    }

    /**
     * Reads a raw JSON value (an integral Number, a numeric String or null) as a non-negative int,
     * recording REQUIRED / NOT_AN_INTEGER / NEGATIVE / TOO_LARGE on the field. Returns 0 when invalid.
     */
    int nonNegativeInt(String field, Object raw) {
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            reject(field, REQUIRED);
            return 0;
        }
        long value;
        if (raw instanceof Long || raw instanceof Integer || raw instanceof Short || raw instanceof Byte) {
            value = ((Number) raw).longValue();
        } else if (raw instanceof Double d) {
            if (d.isNaN() || d.isInfinite() || d != Math.rint(d)) {
                reject(field, NOT_AN_INTEGER);
                return 0;
            }
            if (d > Integer.MAX_VALUE) {
                reject(field, TOO_LARGE);
                return 0;
            }
            if (d < 0) {
                reject(field, NEGATIVE);
                return 0;
            }
            value = d.longValue();
        } else if (raw instanceof String s) {
            try {
                value = Long.parseLong(s.trim());
            } catch (NumberFormatException ex) {
                // beyond long range but still digits: report the bound, not "not an integer"
                reject(field, s.trim().matches("-?\\d+") && !s.trim().startsWith("-") ? TOO_LARGE : NOT_AN_INTEGER);
                return 0;
            }
        } else {
            // the JSON parser only produces Long and Double; anything else is a programming error
            reject(field, NOT_AN_INTEGER);
            return 0;
        }
        if (value < 0) {
            reject(field, NEGATIVE);
            return 0;
        }
        if (value > Integer.MAX_VALUE) {
            reject(field, TOO_LARGE);
            return 0;
        }
        return (int) value;
    }

    void email(String field, String value) {
        if (value != null && !EMAIL.matcher(value).matches()) {
            reject(field, INVALID_EMAIL);
        }
    }

    void reject(String field, String message) {
        errors.putIfAbsent(field, message);
    }

    boolean hasErrors() {
        return !errors.isEmpty();
    }

    Map<String, String> errors() {
        return errors;
    }

    /** @throws ValidationException when at least one error was recorded */
    void throwIfInvalid() {
        if (hasErrors()) {
            throw new ValidationException(errors);
        }
    }
}
