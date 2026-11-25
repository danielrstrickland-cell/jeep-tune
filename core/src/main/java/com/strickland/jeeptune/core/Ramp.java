package com.strickland.jeeptune.core;

/** The colour ramp: pale blue #E6F1FB (low) to amber #BA7517 (high). */
public final class Ramp {
    private Ramp() { }

    public static final int LOW = 0xE6F1FB, HIGH = 0xBA7517, WARM = 0xE8590C, COOL = 0x2F8FE0;

    /** Opaque ARGB for t in 0..1 (clamped; NaN gives the low colour). */
    public static int argb(double t) {
        if (Double.isNaN(t)) t = 0;
        t = Math.max(0, Math.min(1, t));
        return 0xFF000000 | mix(LOW, HIGH, t);
    }

    public static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** rgb floats 0..1 into out[off..off+2]. */
    public static void rgb(int argb, float[] out, int off) {
        out[off] = ((argb >> 16) & 255) / 255f;
        out[off + 1] = ((argb >> 8) & 255) / 255f;
        out[off + 2] = (argb & 255) / 255f;
    }
}
