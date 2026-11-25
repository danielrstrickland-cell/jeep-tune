package com.strickland.jeeptune.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A flash image read-only, with the def applied: decodes tables, curves, scalars, axes. Never changes the bytes. */
public final class CalImage {
    public final byte[] data;
    public final CalDef def;

    public CalImage(byte[] data, CalDef def) {
        this.data = data;
        this.def = def;
    }

    private int u8(int a) {
        if (a < 0 || a >= data.length) throw new IndexOutOfBoundsException(String.format("0x%X is past the end of the file", a));
        return data[a] & 0xFF;
    }

    private int u16(int a) { return (u8(a) << 8) | u8(a + 1); }

    // ---------- axes ----------

    /** An axis decoded: values (scaled) and the raw numbers; null when the image is too short. */
    private double[] axisValues(String id) {
        CalDef.Axis ax = def.axes.get(id);
        if (ax == null) return null;
        try {
            if (ax.curveX) {
                int n = u8(ax.addr) & 0x7F;
                double[] v = new double[n];
                for (int i = 0; i < n; i++) v[i] = ax.scale.apply(u8(ax.addr + 1 + 2 * i));
                return v;
            }
            double[] v = new double[ax.count];
            for (int i = 0; i < ax.count; i++) v[i] = ax.scale.apply(u16(ax.addr + 2 * i));
            return v;
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    // ---------- tables ----------

    public CalTable table(CalDef.TableDef t) {
        CalTable out = new CalTable(t);
        try {
            if (t.isCurve()) decodeCurve(t, out);
            else decodeGrid(t, out);
        } catch (IndexOutOfBoundsException e) {
            out.error = "Not in this file: " + e.getMessage();
        }
        return out;
    }

    private void decodeCurve(CalDef.TableDef t, CalTable o) {
        int hdr = u8(t.addr);
        int n = hdr & 0x7F;
        if (n == 0) { o.error = "Empty curve (header 0x" + Integer.toHexString(hdr) + ")"; return; }
        boolean signed = (hdr & 0x80) != 0 || t.yScale.signed;
        o.curve = true;
        o.rows = 1;
        o.cols = n;
        o.signed = signed;
        o.cellSize = 1;
        o.start = t.addr;
        o.end = t.addr + 1 + 2 * n;
        o.colAxis = new double[n];
        o.rowAxis = new double[]{0};
        o.raw = new int[1][n];
        o.addr = new int[1][n];
        o.val = new double[1][n];
        o.xRaw = new int[n];
        o.xAddr = new int[n];
        for (int i = 0; i < n; i++) {
            o.xAddr[i] = t.addr + 1 + 2 * i;
            o.xRaw[i] = u8(o.xAddr[i]);
            o.colAxis[i] = t.xScale.apply(o.xRaw[i]);
            o.addr[0][i] = o.xAddr[i] + 1;
            int y = u8(o.addr[0][i]);
            if (signed && y > 127) y -= 256;
            o.raw[0][i] = y;
            o.val[0][i] = t.yScale.apply(y);
        }
        o.colKnown = true;
        o.colUnit = t.xScale.unit;
        o.unit = t.yScale.unit;
        o.colDec = Units.decimals(o.colUnit, t.xScale.decimals());
        o.dec = Units.decimals(o.unit, t.yScale.decimals());
    }

    private void decodeGrid(CalDef.TableDef t, CalTable o) {
        boolean word = "table3d_word".equals(t.kind);
        int rows = u8(t.addr), cols = u8(t.addr + 1), flag = u8(t.addr + 2);
        if (rows == 0 || cols == 0) { o.error = "Empty table (" + rows + " x " + cols + ")"; return; }
        int dataAt = t.addr + (word ? 4 : 3);
        o.rows = rows;
        o.cols = cols;
        o.cellSize = word ? 2 : 1;
        o.signed = !word && flag == 0xFF;
        o.start = t.addr;
        o.end = dataAt + rows * cols * o.cellSize;
        o.raw = new int[rows][cols];
        o.addr = new int[rows][cols];
        o.val = new double[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int a = dataAt + (r * cols + c) * o.cellSize;
                int v = word ? u16(a) : u8(a);
                if (o.signed && v > 127) v -= 256;
                o.addr[r][c] = a;
                o.raw[r][c] = v;
                o.val[r][c] = t.scale.apply(v);
            }
        }
        o.unit = t.scale.unit;
        o.dec = Units.decimals(o.unit, t.scale.decimals());
        double[] ra = axisFor(t.rows, rows), ca = axisFor(t.cols, cols);
        o.rowKnown = ra != null;
        o.colKnown = ca != null;
        o.rowAxis = ra != null ? ra : indices(rows);
        o.colAxis = ca != null ? ca : indices(cols);
        CalDef.Axis rax = def.axes.get(t.rows), cax = def.axes.get(t.cols);
        o.rowUnit = o.rowKnown ? rax.scale.unit : "index";
        o.colUnit = o.colKnown ? cax.scale.unit : "index";
        o.rowDec = o.rowKnown ? Units.decimals(o.rowUnit, Math.max(0, rax.scale.decimals() - 1)) : 0;
        o.colDec = o.colKnown ? Units.decimals(o.colUnit, cax.scale.decimals()) : 0;
    }

    private double[] axisFor(String id, int n) {
        if (id == null) return null;
        double[] v = axisValues(id);
        return v != null && v.length == n ? v : null;
    }

    private static double[] indices(int n) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = i;
        return v;
    }

    // ---------- scalars ----------

    public static final class Scalar {
        public final CalDef.ScalarDef def;
        public final long raw;
        public final double value;
        public final String error;

        Scalar(CalDef.ScalarDef def, long raw, double value, String error) {
            this.def = def;
            this.raw = raw;
            this.value = value;
            this.error = error;
        }

        public String valueText() {
            if (error != null) return "n/a";
            int d = Units.decimals(def.scale.unit, def.scale.decimals());
            String v = d == 0 ? Long.toString(Math.round(value)) : String.format(Locale.US, "%." + d + "f", value);
            return v + " " + def.scale.unit;
        }

        /** "Rev limit (fuel cut)  5300 rpm  [raw 42400 @ 3A78]  stock 5300  ?" */
        public String line() {
            StringBuilder sb = new StringBuilder(def.cleanName()).append(": ").append(valueText());
            if (error == null) sb.append("  [raw ").append(raw).append(" @ ").append(String.format("%04X", def.addr)).append(']');
            if (def.stock != null) sb.append("  stock ").append(def.stock);
            if (def.uncertain()) sb.append("  ?");
            return sb.toString();
        }
    }

    public List<Scalar> scalars() {
        List<Scalar> out = new ArrayList<>();
        for (CalDef.ScalarDef s : def.scalars) out.add(scalar(s));
        return out;
    }

    public Scalar scalar(CalDef.ScalarDef s) {
        try {
            long raw = 0;
            for (int i = 0; i < s.size(); i++) raw = (raw << 8) | u8(s.addr + i);
            return new Scalar(s, raw, s.scale.apply(raw), null);
        } catch (IndexOutOfBoundsException e) {
            return new Scalar(s, 0, Double.NaN, e.getMessage());
        }
    }

    // ---------- file facts ----------

    public String sha256() {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class PartCheck {
        public final boolean sizeOk, partFound, shaMatchesDef;
        public final String text;

        PartCheck(boolean sizeOk, boolean partFound, boolean shaMatchesDef, String text) {
            this.sizeOk = sizeOk;
            this.partFound = partFound;
            this.shaMatchesDef = shaMatchesDef;
            this.text = text;
        }

        public boolean good() { return sizeOk && partFound; }
    }

    /** Flash addresses where the part number is stored as BCD digits (two per byte). */
    public static final int[] PART_ADDRS = {0x273F, 0x2746};
    public static final int PART_BYTES = 4;

    /** Digits of a BCD number at addr, or null when the file is too short or a nibble is not 0-9. */
    public String bcd(int addr, int bytes) {
        if (addr < 0 || addr + bytes > data.length) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes; i++) {
            int b = data[addr + i] & 0xFF, hi = b >> 4, lo = b & 15;
            if (hi > 9 || lo > 9) return null;
            sb.append((char) ('0' + hi)).append((char) ('0' + lo));
        }
        return sb.toString();
    }

    /**
     * Checks the part number (BCD at 0x273F, also at 0x2746) against the def's, plus the image size and the def's
     * reference hash. Text is "Part 56041519 ✓" or says what the file holds instead.
     */
    public PartCheck partCheck() {
        boolean size = def.imageSize == 0 || data.length == def.imageSize;
        String want = def.part.length() >= 8 ? def.part.substring(0, 8) : def.part;
        String a = bcd(PART_ADDRS[0], PART_BYTES), b = bcd(PART_ADDRS[1], PART_BYTES);
        boolean found = !want.isEmpty() && (want.equals(a) || want.equals(b));
        boolean sha = !def.imageSha256.isEmpty() && def.imageSha256.equalsIgnoreCase(sha256());
        String t;
        if (!size) t = "Size " + data.length + " bytes, def expects " + def.imageSize;
        else if (found) t = "Part " + want + " \u2713";
        else t = "Part mismatch: file has " + (a == null ? "no readable number" : a) + " at 0x" + Integer.toHexString(PART_ADDRS[0]).toUpperCase(Locale.ROOT)
                + (b == null || b.equals(a) ? "" : " / " + b + " at 0x" + Integer.toHexString(PART_ADDRS[1]).toUpperCase(Locale.ROOT))
                + ", def is " + want;
        if (found && a != null && b != null && !a.equals(b)) t += " (other copy reads " + (want.equals(a) ? b : a) + ")";
        if (sha) t += " \u00B7 identical to the def's reference image";
        return new PartCheck(size, found, sha, t);
    }

    // ---------- known regions (for compare) ----------

    public static final class Region {
        public final int start, end;
        public final String label;
        Region(int start, int end, String label) {
            this.start = start;
            this.end = end;
            this.label = label;
        }
    }

    /** Every byte range the def knows about: tables, curves, scalars and axes. */
    public List<Region> regions() {
        List<Region> r = new ArrayList<>();
        for (CalDef.TableDef t : def.tables) {
            CalTable ct = table(t);
            if (ct.ok()) r.add(new Region(ct.start, ct.end, ct.name()));
        }
        for (CalDef.ScalarDef s : def.scalars) r.add(new Region(s.addr, s.addr + s.size(), s.cleanName()));
        for (CalDef.Axis a : def.axes.values()) {
            try {
                int len = a.curveX ? 1 + 2 * (u8(a.addr) & 0x7F) : 2 * a.count;
                r.add(new Region(a.addr, a.addr + len, "axis " + a.id));
            } catch (IndexOutOfBoundsException ignored) { }
        }
        return r;
    }
}
