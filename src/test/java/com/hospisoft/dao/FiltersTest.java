package com.hospisoft.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FiltersTest {

    @Test
    void likePatternWrapsAndEscapesWildcards() {
        assertEquals("%garcia%", Filters.likePattern("garcia"));
        assertEquals("%50\\%%", Filters.likePattern("50%"));
        assertEquals("%a\\_b%", Filters.likePattern("a_b"));
        assertEquals("%c\\\\d%", Filters.likePattern("c\\d"));
    }

    @Test
    void blankToNullTrims() {
        assertNull(Filters.blankToNull(null));
        assertNull(Filters.blankToNull("   "));
        assertEquals("x", Filters.blankToNull("  x "));
        assertNull(new MedicoFilter(" ", null, "").especialidad());
        assertEquals("ana", new PacienteFilter(null, null, " ana ").texto());
    }

    @Test
    void offsetIsOneBased() {
        assertEquals(0L, Filters.offset(1, 10));
        assertEquals(20L, Filters.offset(3, 10));
        assertEquals(99_999_900L, Filters.offset(1_000_000, 100));
        assertEquals((Integer.MAX_VALUE - 1L) * Integer.MAX_VALUE, Filters.offset(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Filters.offset(0, 10));
        assertThrows(IllegalArgumentException.class, () -> Filters.offset(1, 0));
    }
}
