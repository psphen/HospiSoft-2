package com.hospisoft.service;

import com.hospisoft.model.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public record ExportRows<T>(List<T> items, long total) {
    public static final int MAX_ROWS = 5_000;
    static final int BATCH_SIZE = 500;

    @FunctionalInterface
    public interface PageSource<T> {
        Page<T> page(int page, int pageSize);
    }

    public ExportRows {
        items = List.copyOf(items);
        total = Math.max(total, items.size());
    }

    public boolean truncated() {
        return total > items.size();
    }

    public <R> ExportRows<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = new ArrayList<>(items.size());
        for (T item : items) {
            mapped.add(mapper.apply(item));
        }
        return new ExportRows<>(mapped, total);
    }

    public static <T> ExportRows<T> collect(PageSource<T> source) {
        return collect(source, MAX_ROWS, BATCH_SIZE);
    }

    static <T> ExportRows<T> collect(PageSource<T> source, int maxRows, int batchSize) {
        List<T> items = new ArrayList<>();
        long reported = 0;
        int page = 1;
        while (true) {
            Page<T> result = source.page(page, batchSize);
            reported = result.total();
            items.addAll(result.items());
            if (items.size() >= maxRows) {
                List<T> capped = items.subList(0, maxRows);
                return new ExportRows<>(capped, Math.max(reported, items.size()));
            }
            if (result.items().size() < batchSize || items.size() >= reported) {
                return new ExportRows<>(items, items.size());
            }
            page++;
        }
    }
}
