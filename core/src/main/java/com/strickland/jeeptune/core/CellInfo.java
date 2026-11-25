package com.strickland.jeeptune.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The text shown when a cell is tapped: address, raw and scaled value, and the reference (stock) value if comparing. */
public final class CellInfo {
    private CellInfo() { }

    public static String describe(CalTable t, CalTable ref, int r, int c) {
        if (t == null || !t.ok() || r < 0 || c < 0 || r >= t.rows || c >= t.cols) return "";
        StringBuilder sb = new StringBuilder(t.name()).append("  ·  ");
        if (t.curve) sb.append(num(t.colAxis[c], t.colDec)).append(' ').append(t.colUnit);
        else sb.append(num(t.rowAxis[r], t.rowDec)).append(' ').append(t.rowUnit).append(" × ")
                .append(num(t.colAxis[c], t.colDec)).append(' ').append(t.colUnit);
        int raw = t.raw[r][c];
        int stored = t.cellSize == 2 ? raw & 0xFFFF : raw & 0xFF;
        sb.append("  ·  @").append(String.format("%06X", t.addr[r][c])).append("  ·  raw ")
                .append(String.format(t.cellSize == 2 ? "0x%04X" : "0x%02X", stored)).append(" (").append(raw).append(")  →  ")
                .append(num(t.val[r][c], t.dec)).append(' ').append(t.unit);
        if (ref != null && ref.ok() && ref.rows == t.rows && ref.cols == t.cols) {
            double d = t.val[r][c] - ref.val[r][c];
            if (ref.raw[r][c] == raw) sb.append("  ·  same as compare file");
            else sb.append("  ·  compare ").append(num(ref.val[r][c], ref.dec)).append(" (").append(d > 0 ? "+" : "").append(num(d, t.dec)).append(')');
        }
        return sb.toString();
    }

    public static String num(double v, int dec) { return String.format(Locale.US, "%." + Math.max(0, dec) + "f", v); }

    /** Lines for the pen-hover card: table, position, value, raw and address, hits (if a log is loaded), stock vs this file. */
    public static List<String> tooltip(CalTable t, CalTable ref, int[][] hits, int r, int c) {
        List<String> out = new ArrayList<>();
        if (t == null || !t.ok() || r < 0 || c < 0 || r >= t.rows || c >= t.cols) return out;
        out.add(t.name());
        if (t.curve) out.add(num(t.colAxis[c], t.colDec) + " " + t.colUnit);
        else out.add(num(t.rowAxis[r], t.rowDec) + " " + t.rowUnit + "  \u00D7  " + num(t.colAxis[c], t.colDec) + " " + t.colUnit);
        out.add("Value  " + num(t.val[r][c], t.dec) + " " + t.unit);
        int raw = t.raw[r][c];
        int stored = t.cellSize == 2 ? raw & 0xFFFF : raw & 0xFF;
        out.add(String.format(Locale.US, t.cellSize == 2 ? "Raw  0x%04X (%d)" : "Raw  0x%02X (%d)", stored, raw));
        out.add(String.format(Locale.US, "Address  0x%06X", t.addr[r][c]));
        if (hits != null && !t.curve && r < hits.length && c < hits[r].length) out.add("Log samples  " + hits[r][c]);
        if (ref != null && ref.ok() && ref.rows == t.rows && ref.cols == t.cols) {
            if (ref.raw[r][c] == raw) out.add("Stock  same as this file");
            else {
                double d = t.val[r][c] - ref.val[r][c];
                out.add("Stock  " + num(ref.val[r][c], ref.dec) + " \u2192 this file " + num(t.val[r][c], t.dec)
                        + " (" + (d > 0 ? "+" : "") + num(d, t.dec) + ")");
            }
        }
        return out;
    }
}
