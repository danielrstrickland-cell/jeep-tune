package com.strickland.jeeptune.core;

/** Dongle frame timing for a read session, chosen at the start of every session. */
public enum Timing {
    NORMAL("normal", 120, Frames.TIMING_NORMAL),
    FAST("fast", 30, Frames.TIMING_FAST),
    FASTER("faster", 10, Frames.TIMING_FASTER);

    public final String key;
    public final int ms;
    public final String frame;

    Timing(String key, int ms, String frame) { this.key = key; this.ms = ms; this.frame = frame; }

    public static Timing parse(String s) {
        for (Timing t : values()) if (t.key.equals(s)) return t;
        return NORMAL;
    }
}
