package com.hospisoft.bridge;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Typed readers over a parsed JSON object. Every problem is a {@link BadRequestException}. */
public final class Payload {

    private Payload() {
    }

    /** Parses the payload as an object; an empty payload becomes an empty object. */
    public static Map<String, Object> object(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return Json.parseObject(json);
    }

    public static String requireString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String s) || s.isEmpty()) {
            throw new BadRequestException("Missing field: " + key);
        }
        return s;
    }

    /** Null when absent or null; otherwise the value as text (numbers and booleans are stringified). */
    public static String optString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Map || value instanceof Iterable) {
            throw new BadRequestException("Field must be a scalar: " + key);
        }
        return String.valueOf(value);
    }

    public static long requireLong(Map<String, Object> body, String key) {
        Long value = optLong(body, key);
        if (value == null) {
            throw new BadRequestException("Missing field: " + key);
        }
        return value;
    }

    /** Null when absent/null; accepts integral JSON numbers and numeric strings within long range. */
    public static Long optLong(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite() || d != Math.rint(d)) {
                throw new BadRequestException("Field must be an integer: " + key);
            }
            if (d < Long.MIN_VALUE || d > Long.MAX_VALUE) {
                throw new BadRequestException("Field out of range: " + key);
            }
            return d.longValue();
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            if (s.isBlank()) {
                return null;
            }
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ex) {
                throw new BadRequestException("Field must be an integer: " + key);
            }
        }
        throw new BadRequestException("Field must be a number: " + key);
    }

    /** Like {@link #optLong} but restricted to the int range. */
    public static int optInt(Map<String, Object> body, String key, int defaultValue) {
        Long value = optLong(body, key);
        if (value == null) {
            return defaultValue;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new BadRequestException("Field out of range: " + key);
        }
        return value.intValue();
    }

    /** Null when absent/null; accepts JSON booleans and the strings true/false. */
    public static Boolean optBoolean(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            if (s.isBlank()) {
                return null;
            }
            if (s.equalsIgnoreCase("true")) {
                return Boolean.TRUE;
            }
            if (s.equalsIgnoreCase("false")) {
                return Boolean.FALSE;
            }
        }
        throw new BadRequestException("Field must be a boolean: " + key);
    }

    public static boolean optBoolean(Map<String, Object> body, String key, boolean defaultValue) {
        Boolean value = optBoolean(body, key);
        return value == null ? defaultValue : value;
    }

    /** ISO date (yyyy-MM-dd) or null when absent/blank. */
    public static LocalDate optDate(Map<String, Object> body, String key) {
        String value = optString(body, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("Field must be a date (yyyy-MM-dd): " + key);
        }
    }

    /** Nested object or an empty map when absent. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> optObject(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        throw new BadRequestException("Field must be an object: " + key);
    }
}
