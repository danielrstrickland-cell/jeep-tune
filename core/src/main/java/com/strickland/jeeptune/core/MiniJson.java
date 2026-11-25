package com.strickland.jeeptune.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader (core has no JSON library). Objects become LinkedHashMap, arrays ArrayList, numbers Double,
 * plus String, Boolean and null. Also a few helpers for writing JSON text.
 */
public final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) { this.s = s; }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("trailing text");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("JSON object expected");
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        if (!(o instanceof List)) throw new IllegalArgumentException("JSON array expected");
        return (List<Object>) o;
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private IllegalArgumentException err(String m) { return new IllegalArgumentException("JSON " + m + " at " + i); }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("key expected");
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("':' expected");
            i++;
            ws();
            m.put(k, value());
            ws();
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("',' expected");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw err("',' expected");
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: sb.append(e);
            }
        }
    }

    private Double number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw err("value expected");
        return Double.valueOf(s.substring(st, i));
    }

    // ---- writing ----

    /** A quoted, escaped JSON string (also safe inside a JavaScript literal). */
    public static String quote(String v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** A JSON number; NaN and infinity become null. */
    public static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(Math.round(d * 1e6) / 1e6);
    }
}
