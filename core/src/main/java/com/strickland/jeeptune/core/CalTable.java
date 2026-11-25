package com.strickland.jeeptune.core;

/**
 * One decoded table or curve, independent of how it is drawn. A curve is a grid with one row: colAxis holds its x
 * values and val[0] its y values (xRaw/xAddr tell where the x bytes are).
 */
public final class CalTable {
    public final CalDef.TableDef def;
    public String error;                 // null when decoded
    public boolean curve;
    public int rows, cols;
    public double[] rowAxis = new double[0], colAxis = new double[0];   // axis values, or 0..n-1 when unknown
    public boolean rowKnown, colKnown;
    public String rowUnit = "", colUnit = "", unit = "";
    public int dec, rowDec, colDec;
    public int[][] raw, addr;            // stored numbers (signed applied) and the address of each cell
    public double[][] val;
    public int[] xRaw, xAddr;            // curves only
    public int cellSize = 1;
    public boolean signed;
    public int start, end;               // byte extent in the image, end exclusive

    CalTable(CalDef.TableDef def) { this.def = def; }

    public boolean ok() { return error == null; }

    /** True when the axes are real rpm/MAP values, so a live position can be placed on the grid. */
    public boolean hasRealAxes() { return ok() && !curve && rowKnown && colKnown; }

    public String name() { return def.cleanName(); }

    public int rawAt(int r, int c) { return raw[r][c]; }
    public double valAt(int r, int c) { return val[r][c]; }

    /** Bilinear value at a fractional row/column position (clamped). */
    public double interp(double row, double col) {
        row = Math.max(0, Math.min(rows - 1, row));
        col = Math.max(0, Math.min(cols - 1, col));
        int r0 = (int) Math.floor(row), c0 = (int) Math.floor(col);
        int r1 = Math.min(rows - 1, r0 + 1), c1 = Math.min(cols - 1, c0 + 1);
        double fr = row - r0, fc = col - c0;
        double a = val[r0][c0] * (1 - fc) + val[r0][c1] * fc;
        double b = val[r1][c0] * (1 - fc) + val[r1][c1] * fc;
        return a * (1 - fr) + b * fr;
    }

    /** Fractional index of v along an ascending axis, clamped to 0..n-1. */
    public static double frac(double[] axis, double v) {
        int n = axis.length;
        if (n == 0 || Double.isNaN(v)) return Double.NaN;
        if (v <= axis[0]) return 0;
        if (v >= axis[n - 1]) return n - 1;
        for (int i = 0; i < n - 1; i++) {
            if (v >= axis[i] && v <= axis[i + 1]) {
                double span = axis[i + 1] - axis[i];
                return span <= 0 ? i : i + (v - axis[i]) / span;
            }
        }
        return n - 1;
    }

    /** Index of the axis value nearest to v. */
    public static int nearest(double[] axis, double v) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < axis.length; i++) {
            double d = Math.abs(axis[i] - v);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    public double rowPos(double rpm) { return frac(rowAxis, rpm); }
    public double colPos(double mapKpa) { return frac(colAxis, mapKpa); }
}
