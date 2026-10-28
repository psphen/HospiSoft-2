package com.hospisoft.bridge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON writer and parser.
 *
 * <p>Writer accepts: Map (String keys), Iterable, arrays of objects, String, CharSequence, Character,
 * Number, Boolean, Enum (as its name), null. Anything else is written as {@code String.valueOf}.
 * U+2028 / U+2029 are escaped so the output is also a valid JavaScript string literal.
 *
 * <p>Parser returns: LinkedHashMap, ArrayList, String, Long (integers), Double (decimals/exponents),
 * Boolean, null. Strict: no leading zeros, no leading '+' or '.', finite numbers only, nesting
 * limited to {@link #MAX_DEPTH}. Malformed input raises {@link ParseException}.
 */
public final class Json {

    /** Maximum nesting of objects/arrays accepted by the parser. */
    public static final int MAX_DEPTH = 64;

    /** Unchecked: the text is not valid JSON. */
    public static class ParseException extends BadRequestException {
        public ParseException(String message) {
            super(message);
        }
    }

    private Json() {
    }

    // ---------------------------------------------------------------- writer

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(64);
        writeValue(value, sb);
        return sb.toString();
    }

    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String || value instanceof CharSequence || value instanceof Character) {
            writeString(value.toString(), sb);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Number number) {
            writeNumber(number, sb);
        } else if (value instanceof Enum<?> e) {
            writeString(e.name(), sb);
        } else if (value instanceof Map<?, ?> map) {
            writeMap(map, sb);
        } else if (value instanceof Iterable<?> iterable) {
            writeIterable(iterable, sb);
        } else if (value instanceof Object[] array) {
            writeIterable(List.of(array), sb);
        } else {
            writeString(String.valueOf(value), sb);
        }
    }

    private static void writeNumber(Number number, StringBuilder sb) {
        if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else {
                sb.append(d);
            }
        } else {
            sb.append(number);
        }
    }

    private static void writeMap(Map<?, ?> map, StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(String.valueOf(entry.getKey()), sb);
            sb.append(':');
            writeValue(entry.getValue(), sb);
        }
        sb.append('}');
    }

    private static void writeIterable(Iterable<?> iterable, StringBuilder sb) {
        sb.append('[');
        Iterator<?> it = iterable.iterator();
        while (it.hasNext()) {
            writeValue(it.next(), sb);
            if (it.hasNext()) {
                sb.append(',');
            }
        }
        sb.append(']');
    }

    private static void writeString(String text, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- parser

    public static Object parse(String text) {
        if (text == null) {
            throw new ParseException("JSON text is null");
        }
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    /** Parses text that must be a JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new ParseException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        ParseException error(String message) {
            return new ParseException(message + " at position " + pos);
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            if (atEnd()) {
                throw error("Unexpected end of input");
            }
            return s.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) {
                throw error("Expected '" + c + "'");
            }
            pos++;
        }

        Object readValue(int depth) {
            char c = peek();
            return switch (c) {
                case '{' -> readObject(depth + 1);
                case '[' -> readArray(depth + 1);
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield readNumber();
                    }
                    throw error("Unexpected character '" + c + "'");
                }
            };
        }

        private void checkDepth(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("Nesting deeper than " + MAX_DEPTH + " levels");
            }
        }

        Map<String, Object> readObject(int depth) {
            checkDepth(depth);
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("Expected string key");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, readValue(depth));
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        List<Object> readArray(int depth) {
            checkDepth(depth);
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue(depth));
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw error("Unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append(readUnicodeEscape());
                        default -> throw error("Invalid escape '\\" + e + "'");
                    }
                } else if (c < 0x20) {
                    throw error("Control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        /** Exactly four hex digits; Integer.parseInt would accept signs, so validate by hand. */
        char readUnicodeEscape() {
            if (pos + 4 > s.length()) {
                throw error("Incomplete unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(s.charAt(pos + i), 16);
                if (digit < 0) {
                    throw error("Invalid unicode escape");
                }
                value = (value << 4) | digit;
            }
            pos += 4;
            return (char) value;
        }

        Object readLiteral(String literal, Object value) {
            if (s.startsWith(literal, pos)) {
                pos += literal.length();
                return value;
            }
            throw error("Invalid literal");
        }

        Number readNumber() {
            int start = pos;
            boolean decimal = false;
            if (peek() == '-') {
                pos++;
            }
            if (atEnd() || !isDigit(s.charAt(pos))) {
                throw error("Invalid number");
            }
            if (s.charAt(pos) == '0' && pos + 1 < s.length() && isDigit(s.charAt(pos + 1))) {
                throw error("Leading zeros are not allowed");
            }
            while (!atEnd() && isDigit(s.charAt(pos))) {
                pos++;
            }
            if (!atEnd() && s.charAt(pos) == '.') {
                decimal = true;
                pos++;
                if (atEnd() || !isDigit(s.charAt(pos))) {
                    throw error("Invalid number");
                }
                while (!atEnd() && isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (!atEnd() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                decimal = true;
                pos++;
                if (!atEnd() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                if (atEnd() || !isDigit(s.charAt(pos))) {
                    throw error("Invalid number");
                }
                while (!atEnd() && isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            String token = s.substring(start, pos);
            if (!decimal) {
                try {
                    return Long.parseLong(token);
                } catch (NumberFormatException ex) {
                    // integer too large for long: degrade to double below
                }
            }
            double d;
            try {
                d = Double.parseDouble(token);
            } catch (NumberFormatException ex) {
                throw error("Invalid number");
            }
            if (Double.isInfinite(d) || Double.isNaN(d)) {
                throw error("Number out of range");
            }
            return d;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }
}
