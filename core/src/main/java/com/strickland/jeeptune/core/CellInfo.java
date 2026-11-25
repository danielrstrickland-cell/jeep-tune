package com.strickland.jeeptune.core;

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

    private static String num(double v, int dec) { return String.format(Locale.US, "%." + Math.max(0, dec) + "f", v); }
}
