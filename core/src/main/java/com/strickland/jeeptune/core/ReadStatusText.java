package com.strickland.jeeptune.core;

/** Text for the ongoing notification, e.g. "Reading flash 42% — 110 / 256 KB". */
public final class ReadStatusText {
    private ReadStatusText() { }

    public static String progress(int next, int size) {
        return "Reading flash " + Fmt.percent(next, size) + "% — " + (next / 1024) + " / " + (size / 1024) + " KB";
    }

    /** volts &lt; 0 means unknown. */
    public static String line(int next, int size, double volts, boolean lowBatteryPause) {
        String v = volts >= 0 ? String.format(java.util.Locale.US, "%.1f V", volts) : "-- V";
        if (lowBatteryPause) return "Paused — low battery (" + v + ") · " + (next / 1024) + " / " + (size / 1024) + " KB";
        return progress(next, size) + (volts >= 0 ? " · " + v : "");
    }
}
