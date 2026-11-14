package org.xander.edt.storagebridge.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small, dependency-free JSON codec for the bounded local MCP endpoint. */
final class BridgeMcpJson {
    private BridgeMcpJson() {}

    static Object parse(String input) {
        Parser parser = new Parser(input);
        Object value = parser.value(0);
        parser.whitespace();
        if (parser.index != input.length()) {
            throw new IllegalArgumentException("Trailing JSON content");
        }
        return value;
    }

    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("JSON object required");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Invalid JSON property");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    static String string(Object value) {
        return value instanceof String text ? text : "";
    }

    static String stringify(Object value) {
        StringBuilder result = new StringBuilder();
        append(result, value);
        return result.toString();
    }

    private static void append(StringBuilder result, Object value) {
        if (value == null) {
            result.append("null");
        } else if (value instanceof String text) {
            quote(result, text);
        } else if (value instanceof Boolean || value instanceof Number) {
            result.append(value);
        } else if (value instanceof Map<?, ?> entries) {
            result.append('{');
            boolean first = true;
            for (var entry : entries.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("JSON map key must be a string");
                }
                if (!first) result.append(',');
                first = false;
                quote(result, (String) entry.getKey());
                result.append(':');
                append(result, entry.getValue());
            }
            result.append('}');
        } else if (value instanceof Iterable<?> values) {
            result.append('[');
            boolean first = true;
            for (Object element : values) {
                if (!first) result.append(',');
                first = false;
                append(result, element);
            }
            result.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value");
        }
    }

    private static void quote(StringBuilder result, String value) {
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        result.append("\\u");
                        result.append("0000", 0, 4 - hex.length());
                        result.append(hex);
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        result.append('"');
    }

    private static final class Parser {
        final String text;
        int index;

        Parser(String text) {
            this.text = java.util.Objects.requireNonNull(text);
        }

        void whitespace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
                index++;
            }
        }

        Object value(int depth) {
            if (depth > 40) throw new IllegalArgumentException("JSON nesting limit");
            whitespace();
            if (index >= text.length()) throw new IllegalArgumentException("Unexpected JSON end");
            char c = text.charAt(index);
            if (c == '"') return quoted();
            if (c == '{') return map(depth + 1);
            if (c == '[') return list(depth + 1);
            if (c == 't') { literal("true"); return Boolean.TRUE; }
            if (c == 'f') { literal("false"); return Boolean.FALSE; }
            if (c == 'n') { literal("null"); return null; }
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw new IllegalArgumentException("Invalid JSON token");
        }

        Map<String, Object> map(int depth) {
            index++;
            Map<String, Object> map = new LinkedHashMap<>();
            whitespace();
            if (consume('}')) return map;
            do {
                whitespace();
                if (index >= text.length() || text.charAt(index) != '"') {
                    throw new IllegalArgumentException("Invalid JSON object key");
                }
                String key = quoted();
                whitespace();
                expect(':');
                Object value = value(depth);
                if (map.putIfAbsent(key, value) != null) {
                    throw new IllegalArgumentException("Duplicate JSON key");
                }
                whitespace();
                if (consume('}')) return map;
                expect(',');
            } while (true);
        }

        List<Object> list(int depth) {
            index++;
            List<Object> values = new ArrayList<>();
            whitespace();
            if (consume(']')) return values;
            do {
                values.add(value(depth));
                whitespace();
                if (consume(']')) return values;
                expect(',');
            } while (true);
        }

        String quoted() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') return result.toString();
                if (c < 0x20) throw new IllegalArgumentException("Invalid JSON string");
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                if (index >= text.length()) throw new IllegalArgumentException("Invalid JSON escape");
                switch (text.charAt(index++)) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (index + 4 > text.length()) {
                            throw new IllegalArgumentException("Invalid JSON unicode escape");
                        }
                        try {
                            result.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new IllegalArgumentException("Invalid JSON unicode escape");
                        }
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Invalid JSON escape");
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        Number number() {
            int start = index;
            consume('-');
            if (consume('0')) {
                if (index < text.length() && Character.isDigit(text.charAt(index))) {
                    throw new IllegalArgumentException("Invalid JSON number");
                }
            } else {
                digits();
            }
            boolean decimal = false;
            if (consume('.')) { digits(); decimal = true; }
            if (consume('e') || consume('E')) {
                consume('+');
                consume('-');
                digits();
                decimal = true;
            }
            String raw = text.substring(start, index);
            try {
                return decimal ? new java.math.BigDecimal(raw) : Long.valueOf(raw);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid JSON number");
            }
        }

        void digits() {
            int before = index;
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c < '0' || c > '9') break;
                index++;
            }
            if (before == index) throw new IllegalArgumentException("Invalid JSON number");
        }

        void literal(String keyword) {
            if (!text.startsWith(keyword, index)) {
                throw new IllegalArgumentException("Invalid JSON literal");
            }
            index += keyword.length();
        }

        boolean consume(char ch) {
            if (index < text.length() && text.charAt(index) == ch) {
                index++;
                return true;
            }
            return false;
        }

        void expect(char ch) {
            if (!consume(ch)) throw new IllegalArgumentException("Invalid JSON delimiter");
        }
    }
}
