package com.hospisoft.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospisoft.model.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Walking the repository pages for an export: every page until the total or the row cap. */
class ExportRowsTest {

    /** A paged source over {@code data} that records the requested page sizes. */
    private static ExportRows.PageSource<Integer> source(List<Integer> data, long reportedTotal, List<String> calls) {
        return (page, pageSize) -> {
            calls.add(page + "x" + pageSize);
            int from = Math.min(data.size(), (page - 1) * pageSize);
            int to = Math.min(data.size(), from + pageSize);
            return new Page<>(data.subList(from, to), reportedTotal, page, pageSize);
        };
    }

    private static List<Integer> range(int n) {
        return IntStream.rangeClosed(1, n).boxed().toList();
    }

    @Test
    void collectsEveryPageUntilTheTotal() {
        List<String> calls = new ArrayList<>();
        ExportRows<Integer> rows = ExportRows.collect(source(range(25), 25, calls), 100, 10);
        assertEquals(range(25), rows.items());
        assertEquals(25, rows.total());
        assertFalse(rows.truncated());
        assertEquals(List.of("1x10", "2x10", "3x10"), calls, "no extra query after the last page");
    }

    @Test
    void stopsAtTheCapAndRemembersTheRealTotal() {
        List<String> calls = new ArrayList<>();
        ExportRows<Integer> rows = ExportRows.collect(source(range(57), 57, calls), 20, 15);
        assertEquals(range(20), rows.items());
        assertEquals(57, rows.total());
        assertTrue(rows.truncated());
        assertEquals(List.of("1x15", "2x15"), calls);
    }

    @Test
    void stopsOnAnEmptyPageEvenIfTheTotalPromisedMore() {
        List<String> calls = new ArrayList<>();
        ExportRows<Integer> rows = ExportRows.collect(source(range(12), 40, calls), 100, 10);
        assertEquals(12, rows.items().size());
        assertEquals(List.of("1x10", "2x10"), calls);
        assertEquals(12, rows.total(), "the total never claims more rows than really exist when nothing was cut");
        assertFalse(rows.truncated());
    }

    @Test
    void anEmptyResultIsOneQuery() {
        List<String> calls = new ArrayList<>();
        ExportRows<Integer> rows = ExportRows.collect(source(List.of(), 0, calls), 100, 10);
        assertTrue(rows.items().isEmpty());
        assertEquals(List.of("1x10"), calls);
    }

    @Test
    void defaultsAreTheDocumentedCapAndBatch() {
        List<String> calls = new ArrayList<>();
        ExportRows<Integer> rows = ExportRows.collect(source(range(3), 3, calls));
        assertEquals(3, rows.items().size());
        assertEquals(List.of("1x" + ExportRows.BATCH_SIZE), calls);
        assertEquals(5_000, ExportRows.MAX_ROWS);
    }

    @Test
    void mapKeepsTotalAndTruncation() {
        ExportRows<Integer> rows = ExportRows.collect(source(range(30), 30, new ArrayList<>()), 10, 10);
        ExportRows<String> mapped = rows.map(i -> "#" + i);
        assertEquals("#1", mapped.items().get(0));
        assertEquals(10, mapped.items().size());
        assertEquals(30, mapped.total());
        assertTrue(mapped.truncated());
    }
}
