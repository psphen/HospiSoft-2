package com.hospisoft.model;

import java.util.List;

/**
 * One page of a paginated query result. {@code page} is 1-based.
 *
 * @param total number of rows matching the filter across all pages
 */
public record Page<T>(List<T> items, long total, int page, int pageSize) {

    public Page {
        items = List.copyOf(items);
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
    }

    public int totalPages() {
        return (int) Math.max(1, (total + pageSize - 1) / pageSize);
    }

    public static <T> Page<T> empty(int page, int pageSize) {
        return new Page<>(List.of(), 0, page, pageSize);
    }
}
