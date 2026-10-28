package com.hospisoft.dao;

/** Small helpers shared by filters and DAOs. */
public final class Filters {

    /** Escape character declared in every ILIKE clause ({@code ESCAPE '\'}). */
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

    /**
     * Builds the {@code %text%} pattern for an ILIKE parameter. The user text is escaped so that
     * {@code %}, {@code _} and the escape character are matched literally.
     */
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

    /** Pagination offset for a 1-based page number; overflow-safe. */
    public static long offset(int page, int pageSize) {
        if (page < 1 || pageSize < 1) {
            throw new IllegalArgumentException("page and pageSize must be >= 1");
        }
        return Math.multiplyExact((long) page - 1, (long) pageSize);
    }
}
