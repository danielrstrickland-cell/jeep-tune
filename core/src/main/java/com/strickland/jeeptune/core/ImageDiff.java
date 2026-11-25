package com.strickland.jeeptune.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Differences between a current image and a reference (stock) image. Read-only: nothing is written anywhere. */
public final class ImageDiff {
    /** A run of changed bytes in one known area (label) or outside all of them (label null). */
    public static final class Run {
        public final int start, end;        // end exclusive
        public final String label;
        Run(int start, int end, String label) {
            this.start = start;
            this.end = end;
            this.label = label;
        }
    }

    public static final class CellChange {
        public final String table;
        public final int row, col, addr, rawOld, rawNew;
        public final double valOld, valNew;
        public final String unit;
        CellChange(String table, int row, int col, int addr, int rawOld, int rawNew, double valOld, double valNew, String unit) {
            this.table = table;
            this.row = row;
            this.col = col;
            this.addr = addr;
            this.rawOld = rawOld;
            this.rawNew = rawNew;
            this.valOld = valOld;
            this.valNew = valNew;
            this.unit = unit;
        }
    }

    public int changedBytes;
    public boolean sizeDiffers;
    public final List<Run> runs = new ArrayList<>();
    public final List<Run> outside = new ArrayList<>();
    public final List<CellChange> cells = new ArrayList<>();

    public static ImageDiff compare(CalImage cur, CalImage ref) {
        ImageDiff d = new ImageDiff();
        d.sizeDiffers = cur.data.length != ref.data.length;
        int n = Math.min(cur.data.length, ref.data.length);
        List<CalImage.Region> regions = cur.regions();
        int runStart = -1;
        String runLabel = null;
        for (int i = 0; i <= n; i++) {
            boolean diff = i < n && cur.data[i] != ref.data[i];
            String label = diff ? labelAt(regions, i) : null;
            if (diff) d.changedBytes++;
            if (runStart >= 0 && (!diff || !same(label, runLabel))) {
                add(d, runStart, i, runLabel);
                runStart = -1;
            }
            if (diff && runStart < 0) {
                runStart = i;
                runLabel = label;
            }
        }
        for (CalDef.TableDef t : cur.def.tables) {
            CalTable a = cur.table(t), b = ref.table(t);
            if (!a.ok() || !b.ok() || a.rows != b.rows || a.cols != b.cols) continue;
            for (int r = 0; r < a.rows; r++) {
                for (int c = 0; c < a.cols; c++) {
                    if (a.raw[r][c] != b.raw[r][c])
                        d.cells.add(new CellChange(a.name(), r, c, a.addr[r][c], b.raw[r][c], a.raw[r][c], b.val[r][c], a.val[r][c], a.unit));
                }
            }
        }
        return d;
    }

    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }

    private static void add(ImageDiff d, int s, int e, String label) {
        Run r = new Run(s, e, label);
        d.runs.add(r);
        if (label == null) d.outside.add(r);
    }

    private static String labelAt(List<CalImage.Region> regions, int a) {
        for (CalImage.Region r : regions) if (a >= r.start && a < r.end) return r.label;
        return null;
    }

    public boolean any() { return changedBytes > 0 || sizeDiffers; }

    /** Readable lines: cell changes first, then changes outside known tables. Capped at max lines. */
    public List<String> lines(int max) {
        List<String> out = new ArrayList<>();
        if (sizeDiffers) out.add("Files are different sizes; compared the first bytes only.");
        out.add(changedBytes + " byte" + (changedBytes == 1 ? "" : "s") + " differ, " + cells.size() + " known table cell"
                + (cells.size() == 1 ? "" : "s") + ", " + outside.size() + " run" + (outside.size() == 1 ? "" : "s") + " outside any known table.");
        for (CellChange c : cells) {
            out.add(String.format(Locale.US, "%s  r%d c%d  @%06X  %s -> %s %s (raw %d -> %d)", c.table, c.row, c.col, c.addr,
                    trim(c.valOld), trim(c.valNew), c.unit, c.rawOld, c.rawNew));
            if (out.size() >= max) break;
        }
        if (out.size() < max) {
            for (Run r : outside) {
                out.add(String.format(Locale.US, "Outside tables  %06X-%06X  (%d byte%s)", r.start, r.end - 1, r.end - r.start, r.end - r.start == 1 ? "" : "s"));
                if (out.size() >= max) break;
            }
        }
        int total = 1 + cells.size() + outside.size() + (sizeDiffers ? 1 : 0);
        if (total > out.size()) out.add("... and " + (total - out.size()) + " more");
        return out;
    }

    private static String trim(double v) {
        String s = String.format(Locale.US, "%.3f", v);
        if (s.indexOf('.') >= 0) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }
}
