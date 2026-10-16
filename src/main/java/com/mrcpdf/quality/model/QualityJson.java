package com.mrcpdf.quality.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal zero-dependency JSON reader/writer for the quality-model artifact.
 *
 * Supports exactly the object/array/number/string structure the artifact needs
 * (no nested objects, no booleans on input beyond null-booleans which arrive as
 * quoted strings; all numbers are stored as doubles).  This is deliberately
 * tiny and unescaped beyond what {@code EscapeParser} needs.
 */
public final class QualityJson {

    private QualityJson() {
    }

    // ──────────────────────────────────────────────────────────────────────
    // Writing
    // ──────────────────────────────────────────────────────────────────────

    public static String str(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    public static String num(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    public static String array(Iterable<?> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object value : values) {
            if (!first) sb.append(',');
            first = false;
            append(sb, value);
        }
        return sb.append(']').toString();
    }

    private static void append(StringBuilder sb, Object value) {
        if (value instanceof Double d) sb.append(num(d));
        else if (value instanceof Float f) sb.append(num(f.doubleValue()));
        else if (value instanceof Integer i) sb.append(i);
        else if (value instanceof Long l) sb.append(l);
        else if (value instanceof Number n) sb.append(num(n.doubleValue()));
        else if (value instanceof String s) sb.append(str(s));
        else throw new IllegalArgumentException("Cannot serialize " + value + " of type "
                + (value == null ? "null" : value.getClass()));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Reading
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Parses a JSON value into {@link Map}/{@link List}/{@link String}/{@link Double}.
     */
    public static Object parse(String json) {
        Parser p = new Parser(json);
        Object out = p.value();
        p.skipWs();
        if (!p.atEnd()) throw new IllegalArgumentException("Trailing JSON content at offset " + p.pos);
        return out;
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        void skipWs() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        char peek() {
            skipWs();
            return src.charAt(pos);
        }

        Object value() {
            skipWs();
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> arr();
                case '"' -> string();
                default -> number();
            };
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                map.put(key, value());
                skipWs();
                char c = src.charAt(pos++);
                if (c == '}') return map;
                if (c != ',') throw error("Expected ',' or '}'");
            }
        }

        List<Object> arr() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skipWs();
                char c = src.charAt(pos++);
                if (c == ']') return list;
                if (c != ',') throw error("Expected ',' or ']'");
            }
        }

        String string() {
            skipWs();
            if (src.charAt(pos) != '"') throw error("Expected string");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                char h = src.charAt(pos++);
                                code = code * 16 + Character.digit(h, 16);
                            }
                            sb.append((char) code);
                        }
                        default -> throw error("Invalid escape '\\" + esc + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Double number() {
            skipWs();
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            return Double.parseDouble(src.substring(start, pos));
        }

        void expect(char c) {
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw error("Expected '" + c + "'");
            }
            pos++;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at offset " + pos + " in " + src);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Builds a pretty-printed artifact object with a fixed key order. */
    public static String artifact(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        int total = fields.size();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            sb.append("  ").append(str(e.getKey())).append(": ");
            if (e.getValue() instanceof String s) sb.append(str(s));
            else if (e.getValue() instanceof Iterable<?> it) sb.append(array(it));
            else if (e.getValue() instanceof Double d) sb.append(num(d));
            else if (e.getValue() instanceof Number n) sb.append(n);
            else throw new IllegalArgumentException("Unsupported artifact field type");
            sb.append(i < total - 1 ? ",\n" : "\n");
            i++;
        }
        return sb.append("}\n").toString();
    }

    /** Converts a raw doubled list into a double[] field helper. */
    static List<Double> asDoubles(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Field '" + field + "' is not a JSON array");
        }
        List<Double> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Number n) out.add(n.doubleValue());
            else throw new IllegalArgumentException("Field '" + field + "' contains a non-number");
        }
        return out;
    }
}