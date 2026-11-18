package com.hospisoft.service;

import com.hospisoft.model.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The rows of a list export: every page of the regular (permission-checked, role-scoped) list
 * query, walked through the existing LIMIT/OFFSET paging until the data runs out or
 * {@link #MAX_ROWS} rows were collected.
 *
 * @param total rows matching the filter; larger than {@code items.size()} only when the cap cut the export
 */
public record ExportRows<T>(List<T> items, long total) {

    /** Hard cap on the rows of one export file. */
    public static final int MAX_ROWS = 5_000;
    /** Page size used while walking the list. */
    static final int BATCH_SIZE = 500;

    /** One page of the list use case, with its own permission and scope checks. */
    @FunctionalInterface
    public interface PageSource<T> {
        Page<T> page(int page, int pageSize);
    }

    public ExportRows {
        items = List.copyOf(items);
        total = Math.max(total, items.size());
    }

    /** True when more rows matched than the export contains. */
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
                // the cap cut the result: keep the real total so the file can say so
                return new ExportRows<>(capped, Math.max(reported, items.size()));
            }
            if (result.items().size() < batchSize || items.size() >= reported) {
                // the data ran out: the rows collected are the whole result, whatever the count said
                return new ExportRows<>(items, items.size());
            }
            page++;
        }
    }
}
