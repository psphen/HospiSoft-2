package com.hospisoft.bridge;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

public final class Payload {

    private Payload() {
    }

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
