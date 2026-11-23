package com.strickland.jeeptune.core;

import java.util.Locale;

/** Text for the screen. */
public final class Fmt {
    private Fmt() { }

    public static String timeLeft(long remainingBytes, long avgMs) {
        if (avgMs <= 0) return "--";
        long s = remainingBytes * avgMs / 1000;
        if (s >= 3600) return "≈ " + (s / 3600) + " h " + ((s % 3600) / 60) + " m";
        if (s >= 60) return "≈ " + (s / 60) + " m";
        return "≈ " + s + " s";
    }

    public static String count(long n) { return String.format(Locale.US, "%,d", n); }

    public static int percent(long done, long total) { return total <= 0 ? 0 : (int) Math.round(done * 100.0 / total); }

    public static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        return s < 60 ? s + " s ago" : (s / 60) + " m ago";
    }

    /** "0097A0  XX x8  XX x8  ascii"; bytes at or past nextAddress show "--". */
    public static String row(byte[] data, int rowAddr, int nextAddress) {
        StringBuilder sb = new StringBuilder(String.format("%06X  ", rowAddr));
        StringBuilder asc = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            int a = rowAddr + i;
            if (a < nextAddress && a < data.length) {
                int v = data[a] & 0xff;
                sb.append(String.format("%02X", v));
                asc.append(v >= 32 && v < 127 ? (char) v : '.');
            } else {
                sb.append("--");
                asc.append(' ');
            }
            sb.append(i == 7 ? "  " : " ");
        }
        return sb.append(' ').append(asc.toString().replaceAll("\\s+$", "")).toString();
    }

    /** The last rows (9 by default) ending a little past the read position. */
    public static String rows(byte[] data, int nextAddress, int count) {
        int cur = nextAddress / 16;
        int total = data.length / 16;
        int first = Math.max(0, Math.min(cur - (count - 3), total - count));
        StringBuilder sb = new StringBuilder();
        for (int r = first; r < first + count && r < total; r++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(row(data, r * 16, nextAddress));
        }
        return sb.toString();
    }
}
