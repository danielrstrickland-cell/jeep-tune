package com.strickland.jeeptune.core;

/**
 * Pause/resume decision for the battery during a read. Pauses when the voltage falls below
 * {@link #PAUSE_BELOW}; resumes only once it is above {@link #RESUME_ABOVE} (the gap stops it flapping).
 * Pure logic: no I/O, no clock.
 */
public final class BatteryGuard {
    public static final double PAUSE_BELOW = 12.0, RESUME_ABOVE = 12.4;
    /** Voltage is checked at least this often while reading, and at least every {@link ReadRunner#CHECK_EVERY} bytes. */
    public static final long CHECK_EVERY_MS = 5000;
    /** While paused the battery is re-checked this often. */
    public static final long PAUSED_POLL_MS = 2000;

    private boolean paused;

    public boolean paused() { return paused; }

    /** Feeds one voltage reading; returns true while the read should be paused. */
    public boolean update(double volts) {
        if (paused) {
            if (volts > RESUME_ABOVE) paused = false;
        } else if (volts < PAUSE_BELOW) {
            paused = true;
        }
        return paused;
    }
}
