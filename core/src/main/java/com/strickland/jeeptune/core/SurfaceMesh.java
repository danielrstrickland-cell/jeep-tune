package com.strickland.jeeptune.core;

/**
 * Geometry for the native 3D view, built from a decoded table with no Android classes so it can be tested. Index space is
 * even: columns (MAP) run left to right along x, rows (rpm) run front (row 0) to back along z, the value is height y.
 * Vertex layout: position(3) normal(3) colour(4). Lines: position(3) colour(4).
 */
public final class SurfaceMesh {
    public static final int FLOATS = 10, LINE_FLOATS = 7;
    public static final double HEIGHT = 0.7, ZS = 0.9, LINE_LIFT = 0.004;

    public final int rows, cols;
    public final double[] x, z;
    public final double[][] y;           // height per cell
    public final double lo, hi;          // value range used for height and colour
    public final float[] verts;
    public final short[] indices;
    public float[] ghost;                // same layout, null without a compatible compare table
    public float[] lines;                // grid, base outline, change outlines

    private final int[][] hits;

    private SurfaceMesh(CalTable t, double lo, double hi, int[][] hits) {
        rows = t.rows;
        cols = t.cols;
        this.lo = lo;
        this.hi = hi;
        this.hits = hits;
        x = new double[cols];
        z = new double[rows];
        y = new double[rows][cols];
        verts = new float[rows * cols * FLOATS];
        indices = new short[(rows - 1) * (cols - 1) * 6];
    }

    private static double[] range(CalTable t, CalTable ref) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int r = 0; r < t.rows; r++)
            for (int c = 0; c < t.cols; c++) {
                lo = Math.min(lo, t.val[r][c]);
                hi = Math.max(hi, t.val[r][c]);
                if (ref != null) { lo = Math.min(lo, ref.val[r][c]); hi = Math.max(hi, ref.val[r][c]); }
            }
        return new double[]{lo, hi};
    }

    public static boolean compatible(CalTable t, CalTable ref) {
        return t != null && ref != null && ref.ok() && t.ok() && ref.rows == t.rows && ref.cols == t.cols && ref.curve == t.curve;
    }

    /**
     * @param t must be a decoded grid (not a curve) with at least 2 rows and 2 columns
     * @param ref compare table or null
     * @param hits samples per cell or null; with hitColors the colour follows the hits instead of the value
     * @param tint raised cells warm, lowered cells cool (needs a compatible ref)
     */
    public static SurfaceMesh build(CalTable t, CalTable ref, int[][] hits, boolean hitColors, boolean tint) {
        if (t == null || !t.ok() || t.curve || t.rows < 2 || t.cols < 2) throw new IllegalArgumentException("not a grid");
        boolean cmp = compatible(t, ref);
        double[] rg = range(t, cmp ? ref : null);
        SurfaceMesh m = new SurfaceMesh(t, rg[0], rg[1], hits);
        double span = rg[1] - rg[0];
        for (int c = 0; c < t.cols; c++) m.x[c] = c / (double) (t.cols - 1) * 2 - 1;
        for (int r = 0; r < t.rows; r++) m.z[r] = (1 - 2.0 * r / (t.rows - 1)) * ZS;
        for (int r = 0; r < t.rows; r++)
            for (int c = 0; c < t.cols; c++) m.y[r][c] = span <= 0 ? 0 : HEIGHT * (t.val[r][c] - rg[0]) / span;
        int maxHits = 1;
        if (hits != null) for (int[] row : hits) for (int h : row) maxHits = Math.max(maxHits, h);
        float[] rgb = new float[3];
        for (int r = 0; r < t.rows; r++) {
            for (int c = 0; c < t.cols; c++) {
                int o = (r * t.cols + c) * FLOATS;
                m.verts[o] = (float) m.x[c];
                m.verts[o + 1] = (float) m.y[r][c];
                m.verts[o + 2] = (float) m.z[r];
                double[] n = m.normal(r, c);
                m.verts[o + 3] = (float) n[0];
                m.verts[o + 4] = (float) n[1];
                m.verts[o + 5] = (float) n[2];
                int col;
                if (hitColors && hits != null) col = Ramp.argb(Math.sqrt(hits[r][c] / (double) maxHits));
                else col = Ramp.argb(span <= 0 ? 0 : (t.val[r][c] - rg[0]) / span);
                if (tint && cmp && t.raw[r][c] != ref.raw[r][c])
                    col = Ramp.mix(col & 0xFFFFFF, t.val[r][c] > ref.val[r][c] ? Ramp.WARM : Ramp.COOL, 0.55) | 0xFF000000;
                Ramp.rgb(col, rgb, 0);
                m.verts[o + 6] = rgb[0];
                m.verts[o + 7] = rgb[1];
                m.verts[o + 8] = rgb[2];
                m.verts[o + 9] = 1f;
            }
        }
        int k = 0;
        for (int r = 0; r < t.rows - 1; r++)
            for (int c = 0; c < t.cols - 1; c++) {
                short a = (short) (r * t.cols + c), b = (short) (a + 1), c2 = (short) (a + t.cols), d = (short) (c2 + 1);
                m.indices[k++] = a; m.indices[k++] = b; m.indices[k++] = d;
                m.indices[k++] = a; m.indices[k++] = d; m.indices[k++] = c2;
            }
        if (cmp) m.ghost = ghost(m, t, ref);
        m.lines = m.buildLines(t, cmp ? ref : null);
        return m;
    }

    private static float[] ghost(SurfaceMesh m, CalTable t, CalTable ref) {
        float[] g = new float[m.verts.length];
        double span = m.hi - m.lo;
        double[][] gy = new double[t.rows][t.cols];
        for (int r = 0; r < t.rows; r++)
            for (int c = 0; c < t.cols; c++) gy[r][c] = span <= 0 ? 0 : HEIGHT * (ref.val[r][c] - m.lo) / span;
        for (int r = 0; r < t.rows; r++)
            for (int c = 0; c < t.cols; c++) {
                int o = (r * t.cols + c) * FLOATS;
                g[o] = (float) m.x[c];
                g[o + 1] = (float) gy[r][c];
                g[o + 2] = (float) m.z[r];
                double[] n = normalOf(gy, m.x, m.z, r, c);
                g[o + 3] = (float) n[0];
                g[o + 4] = (float) n[1];
                g[o + 5] = (float) n[2];
                g[o + 6] = 0.50f; g[o + 7] = 0.61f; g[o + 8] = 0.71f; g[o + 9] = 0.30f;
            }
        return g;
    }

    private double[] normal(int r, int c) { return normalOf(y, x, z, r, c); }

    /** Per-vertex normal from central differences of the height grid (one-sided at the edges). */
    static double[] normalOf(double[][] h, double[] x, double[] z, int r, int c) {
        int rows = h.length, cols = h[0].length;
        int c0 = Math.max(0, c - 1), c1 = Math.min(cols - 1, c + 1), r0 = Math.max(0, r - 1), r1 = Math.min(rows - 1, r + 1);
        double dydx = (h[r][c1] - h[r][c0]) / (x[c1] - x[c0]);
        double dydz = (h[r1][c] - h[r0][c]) / (z[r1] - z[r0]);
        double nx = -dydx, ny = 1, nz = -dydz;
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return new double[]{nx / l, ny / l, nz / l};
    }

    private float[] buildLines(CalTable t, CalTable ref) {
        boolean cmp = ref != null;
        int changed = 0;
        if (cmp) for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) if (t.raw[r][c] != ref.raw[r][c]) changed++;
        int segs = rows * (cols - 1) + cols * (rows - 1) + 4 + changed * 4;
        float[] l = new float[segs * 2 * LINE_FLOATS];
        int[] o = {0};
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols - 1; c++)
                seg(l, o, x[c], y[r][c] + LINE_LIFT, z[r], x[c + 1], y[r][c + 1] + LINE_LIFT, z[r], 0.04f, 0.06f, 0.09f, 0.40f);
        for (int c = 0; c < cols; c++)
            for (int r = 0; r < rows - 1; r++)
                seg(l, o, x[c], y[r][c] + LINE_LIFT, z[r], x[c], y[r + 1][c] + LINE_LIFT, z[r + 1], 0.04f, 0.06f, 0.09f, 0.40f);
        double xl = x[0], xr = x[cols - 1], zf = z[0], zb = z[rows - 1];
        seg(l, o, xl, 0, zf, xr, 0, zf, 0.45f, 0.5f, 0.55f, 0.8f);
        seg(l, o, xr, 0, zf, xr, 0, zb, 0.45f, 0.5f, 0.55f, 0.8f);
        seg(l, o, xr, 0, zb, xl, 0, zb, 0.45f, 0.5f, 0.55f, 0.8f);
        seg(l, o, xl, 0, zb, xl, 0, zf, 0.45f, 0.5f, 0.55f, 0.8f);
        if (cmp) {
            float[] rgb = new float[3];
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++) {
                    if (t.raw[r][c] == ref.raw[r][c]) continue;
                    Ramp.rgb(t.val[r][c] > ref.val[r][c] ? Ramp.WARM : Ramp.COOL, rgb, 0);
                    square(l, o, r, c, 0.4, rgb[0], rgb[1], rgb[2], 1f);
                }
        }
        return l;
    }

    /** Lines for a square ring (half-size = frac of a cell step) around one cell, at the surface height. */
    public float[] ring(int r, int c, double frac, float cr, float cg, float cb) {
        float[] l = new float[4 * 2 * LINE_FLOATS];
        square(l, new int[]{0}, r, c, frac, cr, cg, cb, 1f);
        return l;
    }

    private void square(float[] l, int[] o, int r, int c, double frac, float cr, float cg, float cb, float ca) {
        double hx = 2.0 / (cols - 1) * frac, hz = 2.0 * ZS / (rows - 1) * frac, yy = y[r][c] + 0.012;
        double x0 = x[c] - hx, x1 = x[c] + hx, z0 = z[r] - hz, z1 = z[r] + hz;
        seg(l, o, x0, yy, z0, x1, yy, z0, cr, cg, cb, ca);
        seg(l, o, x1, yy, z0, x1, yy, z1, cr, cg, cb, ca);
        seg(l, o, x1, yy, z1, x0, yy, z1, cr, cg, cb, ca);
        seg(l, o, x0, yy, z1, x0, yy, z0, cr, cg, cb, ca);
    }

    private static void seg(float[] l, int[] o, double x0, double y0, double z0, double x1, double y1, double z1,
                            float r, float g, float b, float a) {
        int i = o[0];
        l[i++] = (float) x0; l[i++] = (float) y0; l[i++] = (float) z0; l[i++] = r; l[i++] = g; l[i++] = b; l[i++] = a;
        l[i++] = (float) x1; l[i++] = (float) y1; l[i++] = (float) z1; l[i++] = r; l[i++] = g; l[i++] = b; l[i++] = a;
        o[0] = i;
    }

    // ---------- positions and picking ----------

    /** World position of a fractional cell position (row, col); out[3]. y is the surface height there. */
    public void worldPos(double row, double col, double[] out) {
        row = Math.max(0, Math.min(rows - 1, row));
        col = Math.max(0, Math.min(cols - 1, col));
        int r0 = (int) Math.floor(row), c0 = (int) Math.floor(col);
        int r1 = Math.min(rows - 1, r0 + 1), c1 = Math.min(cols - 1, c0 + 1);
        double fr = row - r0, fc = col - c0;
        double a = y[r0][c0] * (1 - fc) + y[r0][c1] * fc, b = y[r1][c0] * (1 - fc) + y[r1][c1] * fc;
        out[0] = x[c0] * (1 - fc) + x[c1] * fc;
        out[1] = a * (1 - fr) + b * fr;
        out[2] = z[r0] * (1 - fr) + z[r1] * fr;
    }

    /** Nearest cell {row, col} under a ray (Moller-Trumbore over the surface triangles), or null when it misses. */
    public int[] pick(double[] o, double[] d) {
        double best = Double.MAX_VALUE;
        double[] hit = null;
        double[] p = new double[3];
        for (int r = 0; r < rows - 1; r++)
            for (int c = 0; c < cols - 1; c++) {
                double[][] v = {pt(r, c), pt(r, c + 1), pt(r + 1, c + 1), pt(r + 1, c)};
                double t1 = tri(o, d, v[0], v[1], v[2]), t2 = tri(o, d, v[0], v[2], v[3]);
                double t = Math.min(t1 < 0 ? Double.MAX_VALUE : t1, t2 < 0 ? Double.MAX_VALUE : t2);
                if (t < best) { best = t; hit = new double[]{o[0] + d[0] * t, o[2] + d[2] * t}; }
            }
        if (hit == null) return null;
        int bc = 0, br = 0;
        double bd = Double.MAX_VALUE;
        for (int c = 0; c < cols; c++) if (Math.abs(x[c] - hit[0]) < bd) { bd = Math.abs(x[c] - hit[0]); bc = c; }
        bd = Double.MAX_VALUE;
        for (int r = 0; r < rows; r++) if (Math.abs(z[r] - hit[1]) < bd) { bd = Math.abs(z[r] - hit[1]); br = r; }
        return new int[]{br, bc};
    }

    private double[] pt(int r, int c) { return new double[]{x[c], y[r][c], z[r]}; }

    private static double tri(double[] o, double[] d, double[] a, double[] b, double[] c) {
        double[] e1 = Mat4.sub(b, a), e2 = Mat4.sub(c, a);
        double[] pv = Mat4.cross(d, e2);
        double det = Mat4.dot(e1, pv);
        if (Math.abs(det) < 1e-12) return -1;
        double inv = 1 / det;
        double[] tv = Mat4.sub(o, a);
        double u = Mat4.dot(tv, pv) * inv;
        if (u < 0 || u > 1) return -1;
        double[] qv = Mat4.cross(tv, e1);
        double v = Mat4.dot(d, qv) * inv;
        if (v < 0 || u + v > 1) return -1;
        double t = Mat4.dot(e2, qv) * inv;
        return t > 0 ? t : -1;
    }
}
