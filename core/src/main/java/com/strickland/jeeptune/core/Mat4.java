package com.strickland.jeeptune.core;

/** Minimal 4x4 matrix maths (column-major like OpenGL), plain doubles so it is testable without Android. */
public final class Mat4 {
    private Mat4() { }

    public static void identity(double[] m) {
        for (int i = 0; i < 16; i++) m[i] = (i % 5 == 0) ? 1 : 0;
    }

    public static void perspective(double[] m, double fovyDeg, double aspect, double near, double far) {
        double f = 1.0 / Math.tan(Math.toRadians(fovyDeg) / 2);
        for (int i = 0; i < 16; i++) m[i] = 0;
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1;
        m[14] = 2 * far * near / (near - far);
    }

    public static void lookAt(double[] m, double[] eye, double[] center, double[] up) {
        double[] f = norm(sub(center, eye));
        double[] s = norm(cross(f, up));
        double[] u = cross(s, f);
        m[0] = s[0]; m[4] = s[1]; m[8] = s[2];  m[12] = -dot(s, eye);
        m[1] = u[0]; m[5] = u[1]; m[9] = u[2];  m[13] = -dot(u, eye);
        m[2] = -f[0]; m[6] = -f[1]; m[10] = -f[2]; m[14] = dot(f, eye);
        m[3] = 0; m[7] = 0; m[11] = 0; m[15] = 1;
    }

    /** out = a * b (out must not alias a or b). */
    public static void mul(double[] out, double[] a, double[] b) {
        for (int j = 0; j < 4; j++)
            for (int i = 0; i < 4; i++) {
                double s = 0;
                for (int k = 0; k < 4; k++) s += a[k * 4 + i] * b[j * 4 + k];
                out[j * 4 + i] = s;
            }
    }

    /** Gauss-Jordan inverse; false when singular. */
    public static boolean invert(double[] out, double[] in) {
        double[][] a = new double[4][8];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) a[i][j] = in[j * 4 + i];
            a[i][4 + i] = 1;
        }
        for (int c = 0; c < 4; c++) {
            int p = c;
            for (int r = c + 1; r < 4; r++) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r;
            if (Math.abs(a[p][c]) < 1e-12) return false;
            double[] t = a[c]; a[c] = a[p]; a[p] = t;
            double d = a[c][c];
            for (int j = 0; j < 8; j++) a[c][j] /= d;
            for (int r = 0; r < 4; r++) {
                if (r == c) continue;
                double f = a[r][c];
                if (f == 0) continue;
                for (int j = 0; j < 8; j++) a[r][j] -= f * a[c][j];
            }
        }
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) out[j * 4 + i] = a[i][4 + j];
        return true;
    }

    /** out4 = m * (x, y, z, w). */
    public static void transform(double[] m, double x, double y, double z, double w, double[] out4) {
        for (int i = 0; i < 4; i++) out4[i] = m[i] * x + m[4 + i] * y + m[8 + i] * z + m[12 + i] * w;
    }

    public static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    public static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    public static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
    public static double[] norm(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return l == 0 ? a : new double[]{a[0] / l, a[1] / l, a[2] / l};
    }
}
