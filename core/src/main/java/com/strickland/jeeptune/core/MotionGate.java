package com.strickland.jeeptune.core;

/** Tilt-to-orbit is off while the Jeep is moving: a LIVE broadcast in the last 5 s, or GPS speed over 5 mph. */
public final class MotionGate {
    public static final long LIVE_WINDOW_MS = 5000;
    public static final double MAX_MPH = 5;
    private MotionGate() { }

    public static double mph(double metersPerSecond) { return metersPerSecond * 2.236936; }

    /** lastLiveMs and now on the same clock; lastLiveMs <= 0 means none yet; gpsMph NaN means unknown. */
    public static boolean moving(long nowMs, long lastLiveMs, double gpsMph) {
        if (lastLiveMs > 0 && nowMs - lastLiveMs <= LIVE_WINDOW_MS) return true;
        return !Double.isNaN(gpsMph) && gpsMph > MAX_MPH;
    }
}
