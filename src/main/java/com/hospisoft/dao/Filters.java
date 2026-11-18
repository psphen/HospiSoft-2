package com.hospisoft.dao;

public final class Filters {
    public static final char LIKE_ESCAPE = '\\';

    private Filters() {
    }

    public static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String likePattern(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 2);
        sb.append('%');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) {
                sb.append(LIKE_ESCAPE);
            }
            sb.append(c);
        }
        sb.append('%');
        return sb.toString();
    }

    public static long offset(int page, int pageSize) {
        if (page < 1 || pageSize < 1) {
            throw new IllegalArgumentException("page and pageSize must be >= 1");
        }
        return Math.multiplyExact((long) page - 1, (long) pageSize);
    }
}
