package com.hospisoft.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageTest {

    @Test
    void totalPagesRoundsUpAndIsAtLeastOne() {
        assertEquals(1, Page.empty(1, 10).totalPages());
        assertEquals(1, new Page<>(List.of(1), 10, 1, 10).totalPages());
        assertEquals(2, new Page<>(List.of(1), 11, 1, 10).totalPages());
        assertEquals(49, new Page<>(List.of(1), 486, 1, 10).totalPages());
        assertThrows(IllegalArgumentException.class, () -> new Page<>(List.of(), 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new Page<>(List.of(), 0, 1, 0));
    }
}
