package com.strickland.jeeptune.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A Jeep Dash tune log (CSV): '#' header lines, one header row, then samples. Only rpm and MAP are used. */
public final class TuneCsv {
    public String profile = "";
    public double[] t = new double[0], rpm = new double[0], map = new double[0];   // t in ms

    public int count() { return rpm.length; }

    public double durationMs() { return count() < 2 ? 0 : t[count() - 1] - t[0]; }

    public static TuneCsv parse(String text) {
        TuneCsv out = new TuneCsv();
        String[] lines = text.split("\r?\n");
        int li = 0;
        for (; li < lines.length; li++) {
            String l = lines[li].trim();
            if (l.isEmpty()) continue;
            if (!l.startsWith("#")) break;
            String low = l.toLowerCase(Locale.ROOT);
            int k = low.indexOf("profile=");
            if (low.startsWith("#") && k >= 0) out.profile = l.substring(k + 8).trim();
        }
        if (li >= lines.length) throw new IllegalArgumentException("No header row in this log");
        String[] head = lines[li++].split(",", -1);
        int ti = -1, ri = -1, mi = -1;
        for (int i = 0; i < head.length; i++) {
            String h = head[i].trim().toLowerCase(Locale.ROOT);
            if (ri < 0 && startsWord(h, "rpm")) ri = i;
            else if (mi < 0 && startsWord(h, "map")) mi = i;
            else if (ti < 0 && (h.equals("time_ms") || startsWord(h, "time"))) ti = i;
        }
        if (ri < 0) throw new IllegalArgumentException("This log has no rpm column");
        if (mi < 0) throw new IllegalArgumentException("This log has no MAP column");
        List<double[]> rows = new ArrayList<>();
        for (; li < lines.length; li++) {
            String l = lines[li].trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] f = l.split(",", -1);
            double r = cell(f, ri), m = cell(f, mi);
            if (Double.isNaN(r) || Double.isNaN(m)) continue;
            double tm = ti >= 0 ? cell(f, ti) : Double.NaN;
            if (Double.isNaN(tm)) tm = rows.size() * 100.0;
            rows.add(new double[]{tm, r, m});
        }
        int n = rows.size();
        out.t = new double[n];
        out.rpm = new double[n];
        out.map = new double[n];
        for (int i = 0; i < n; i++) {
            double[] r = rows.get(i);
            out.t[i] = r[0];
            out.rpm[i] = r[1];
            out.map[i] = r[2];
        }
        return out;
    }

    private static boolean startsWord(String h, String w) {
        return h.startsWith(w) && (h.length() == w.length() || !Character.isLetterOrDigit(h.charAt(w.length())) || h.charAt(w.length()) == '_');
    }

    private static double cell(String[] f, int i) {
        if (i >= f.length) return Double.NaN;
        String s = f[i].trim();
        if (s.isEmpty()) return Double.NaN;
        try {
            double d = Double.parseDouble(s);
            return Double.isInfinite(d) ? Double.NaN : d;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Samples per nearest rpm/MAP cell of a table with real axes. */
    public int[][] hits(CalTable t) {
        int[][] h = new int[t.rows][t.cols];
        for (int i = 0; i < count(); i++) h[CalTable.nearest(t.rowAxis, rpm[i])][CalTable.nearest(t.colAxis, map[i])]++;
        return h;
    }
}
