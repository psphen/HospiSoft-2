package com.hospisoft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundTripsNestedMapAndList() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("id", 7L);
        inner.put("tags", List.of("a", "b"));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ok", true);
        root.put("data", inner);
        root.put("nothing", null);

        String text = Json.write(root);
        assertEquals("{\"ok\":true,\"data\":{\"id\":7,\"tags\":[\"a\",\"b\"]},\"nothing\":null}", text);

        Map<String, Object> parsed = Json.parseObject(text);
        assertEquals(true, parsed.get("ok"));
        assertNull(parsed.get("nothing"));
        assertTrue(parsed.containsKey("nothing"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) parsed.get("data");
        assertEquals(7L, data.get("id"));
        assertEquals(List.of("a", "b"), data.get("tags"));
    }

    @Test
    void escapesStringsWhenWriting() {
        String written = Json.write("say \"hi\"\\ \n\t\r \u0001 ni\u00f1o");
        assertEquals("\"say \\\"hi\\\"\\\\ \\n\\t\\r \\u0001 ni\u00f1o\"", written);
    }

    @Test
    void escapesLineSeparatorsSoOutputIsAlsoAJsStringLiteral() {
        assertEquals("\"a\\u2028b\\u2029c\"", Json.write("a\u2028b\u2029c"));
    }

    @Test
    void unescapesStringsWhenParsing() {
        Object parsed = Json.parse("\"a\\\"b\\\\c\\n\\t\\u00f1\\u2013\\/\"");
        assertEquals("a\"b\\c\n\t\u00f1\u2013/", parsed);
        assertEquals("\u00FF", Json.parse("\"\\u00FF\""));
    }

    @Test
    void parsesNumbersBooleansAndNull() {
        assertEquals(42L, Json.parse("42"));
        assertEquals(0L, Json.parse("0"));
        assertEquals(-3L, Json.parse("-3"));
        assertEquals(1.5, Json.parse("1.5"));
        assertEquals(0.5, Json.parse("0.5"));
        assertEquals(2.0e3, Json.parse("2e3"));
        assertEquals(1.0e-2, Json.parse("1E-2"));
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertEquals(Boolean.FALSE, Json.parse("false"));
        assertNull(Json.parse("null"));
    }

    @Test
    void writesNumbersBooleansNullAndEnums() {
        assertEquals("1", Json.write(1));
        assertEquals("2.5", Json.write(2.5));
        assertEquals("false", Json.write(false));
        assertEquals("null", Json.write(null));
        assertEquals("\"ADMIN\"", Json.write(com.hospisoft.model.Rol.ADMIN));
    }

    @Test
    void toleratesWhitespace() {
        Map<String, Object> parsed = Json.parseObject(" { \"a\" : [ 1 , 2 ] , \"b\" : { } } ");
        assertEquals(List.of(1L, 2L), parsed.get("a"));
        assertEquals(Map.of(), parsed.get("b"));
    }

    @Test
    void malformedInputThrows() {
        assertThrows(Json.ParseException.class, () -> Json.parse(""));
        assertThrows(Json.ParseException.class, () -> Json.parse("{"));
        assertThrows(Json.ParseException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(Json.ParseException.class, () -> Json.parse("[1,]"));
        assertThrows(Json.ParseException.class, () -> Json.parse("{\"a\":1,}"));
        assertThrows(Json.ParseException.class, () -> Json.parse("\"unterminated"));
        assertThrows(Json.ParseException.class, () -> Json.parse("tru"));
        assertThrows(Json.ParseException.class, () -> Json.parse("{\"a\":1} extra"));
        assertThrows(Json.ParseException.class, () -> Json.parse("1 2"));
        assertThrows(Json.ParseException.class, () -> Json.parse(null));
        assertThrows(Json.ParseException.class, () -> Json.parseObject("[1]"));
    }

    @Test
    void strictNumbers() {
        assertThrows(Json.ParseException.class, () -> Json.parse("01"));
        assertThrows(Json.ParseException.class, () -> Json.parse("-01"));
        assertThrows(Json.ParseException.class, () -> Json.parse(".5"));
        assertThrows(Json.ParseException.class, () -> Json.parse("+1"));
        assertThrows(Json.ParseException.class, () -> Json.parse("1."));
        assertThrows(Json.ParseException.class, () -> Json.parse("1e"));
        assertThrows(Json.ParseException.class, () -> Json.parse("1E400"));
        assertThrows(Json.ParseException.class, () -> Json.parse("-1E400"));
        assertEquals(1.0E308, Json.parse("1E308"));
    }

    @Test
    void unicodeEscapesNeedExactlyFourHexDigits() {
        assertThrows(Json.ParseException.class, () -> Json.parse("\"\\u+041\""));
        assertThrows(Json.ParseException.class, () -> Json.parse("\"\\u-001\""));
        assertThrows(Json.ParseException.class, () -> Json.parse("\"\\u00G1\""));
        assertThrows(Json.ParseException.class, () -> Json.parse("\"\\u00\""));
        assertThrows(Json.ParseException.class, () -> Json.parse("\"\\u 041\""));
    }

    @Test
    void nestingDepthIsLimited() {
        String ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH);
        Json.parse(ok);
        String tooDeep = "[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1);
        Json.ParseException ex = assertThrows(Json.ParseException.class, () -> Json.parse(tooDeep));
        assertTrue(ex.getMessage().contains("Nesting deeper than 64"));
        String deepObjects = "{\"a\":".repeat(Json.MAX_DEPTH + 1) + "1" + "}".repeat(Json.MAX_DEPTH + 1);
        assertThrows(Json.ParseException.class, () -> Json.parse(deepObjects));
    }

    @Test
    void parseExceptionIsABadRequest() {
        assertTrue(BadRequestException.class.isAssignableFrom(Json.ParseException.class));
    }
}
