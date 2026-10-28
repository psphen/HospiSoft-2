package com.hospisoft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PayloadTest {

    @Test
    void emptyPayloadIsAnEmptyObject() {
        assertEquals(Map.of(), Payload.object(null));
        assertEquals(Map.of(), Payload.object("  "));
        assertThrows(BadRequestException.class, () -> Payload.object("[1]"));
    }

    @Test
    void numbersAcceptJsonNumbersAndNumericStrings() {
        Map<String, Object> body = Json.parseObject("{\"a\":5,\"b\":\"7\",\"c\":\"\",\"d\":\"x\",\"e\":true}");
        assertEquals(5L, Payload.requireLong(body, "a"));
        assertEquals(7L, Payload.optLong(body, "b"));
        assertNull(Payload.optLong(body, "c"));
        assertEquals(3, Payload.optInt(body, "missing", 3));
        assertThrows(BadRequestException.class, () -> Payload.optLong(body, "d"));
        assertThrows(BadRequestException.class, () -> Payload.optLong(body, "e"));
        assertThrows(BadRequestException.class, () -> Payload.requireLong(body, "missing"));
    }

    @Test
    void nonIntegralAndOutOfRangeNumbersAreRejected() {
        Map<String, Object> body = Json.parseObject(
            "{\"frac\":3.7,\"whole\":3.0,\"huge\":1e30,\"big\":3000000000,\"neg\":-1.5}");
        assertThrows(BadRequestException.class, () -> Payload.optLong(body, "frac"));
        assertThrows(BadRequestException.class, () -> Payload.optLong(body, "neg"));
        assertEquals(3L, Payload.optLong(body, "whole"));
        assertThrows(BadRequestException.class, () -> Payload.optLong(body, "huge"));
        assertEquals(3000000000L, Payload.optLong(body, "big"));
        assertThrows(BadRequestException.class, () -> Payload.optInt(body, "big", 0));
        assertThrows(BadRequestException.class, () -> Payload.optInt(body, "frac", 0));
    }

    @Test
    void booleansAcceptJsonBooleansAndStrings() {
        Map<String, Object> body = Json.parseObject("{\"a\":false,\"b\":\"TRUE\",\"c\":\"\",\"d\":\"si\"}");
        assertEquals(Boolean.FALSE, Payload.optBoolean(body, "a"));
        assertEquals(Boolean.TRUE, Payload.optBoolean(body, "b"));
        assertNull(Payload.optBoolean(body, "c"));
        assertEquals(true, Payload.optBoolean(body, "missing", true));
        assertThrows(BadRequestException.class, () -> Payload.optBoolean(body, "d"));
    }

    @Test
    void datesAreIsoOrBadRequest() {
        Map<String, Object> body = Json.parseObject("{\"a\":\"2024-02-29\",\"b\":\"\",\"c\":\"2024-13-01\"}");
        assertEquals(LocalDate.of(2024, 2, 29), Payload.optDate(body, "a"));
        assertNull(Payload.optDate(body, "b"));
        assertNull(Payload.optDate(body, "missing"));
        assertThrows(BadRequestException.class, () -> Payload.optDate(body, "c"));
    }

    @Test
    void stringsAndObjects() {
        Map<String, Object> body = Json.parseObject("{\"s\":\"x\",\"n\":3,\"o\":{\"k\":1},\"l\":[1]}");
        assertEquals("x", Payload.optString(body, "s"));
        assertEquals("3", Payload.optString(body, "n"));
        assertEquals(Map.of("k", 1L), Payload.optObject(body, "o"));
        assertEquals(Map.of(), Payload.optObject(body, "missing"));
        assertThrows(BadRequestException.class, () -> Payload.optString(body, "o"));
        assertThrows(BadRequestException.class, () -> Payload.optObject(body, "s"));
        assertThrows(BadRequestException.class, () -> Payload.requireString(body, "n"));
    }
}
