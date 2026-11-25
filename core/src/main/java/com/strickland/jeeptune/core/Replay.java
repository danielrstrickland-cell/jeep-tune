package com.strickland.jeeptune.core;

/**
 * Plays a tune log over a table: a position in log time, play/pause, speed, and the dot and fading trail as fractional
 * row/column positions. No drawing here, so any renderer can use it.
 */
public final class Replay {
    public static final double TRAIL_MS = 3000;   // of real time on screen; scaled by speed in log time
    public static final int TRAIL_POINTS = 40;

    private final TuneCsv log;
    private CalTable table;
    private double speed = 1;
    private boolean playing;
    private double pos;        // ms since the first sample

    public Replay(TuneCsv log, CalTable table) {
        this.log = log;
        this.table = table;
    }

    public void setTable(CalTable t) { table = t; }
    public boolean playing() { return playing; }
    public void play() { if (pos >= length()) pos = 0; playing = log.count() > 1; }
    public void pause() { playing = false; }
    public double speed() { return speed; }
    public void setSpeed(double s) { speed = s; }
    public double length() { return log.durationMs(); }
    public double position() { return pos; }

    /** Jumps by whole seconds of log time (pen/media keys): +1 forward, -1 back. */
    public void step(int seconds) { seek(pos + seconds * 1000.0); }

    public void toggle() { if (playing) pause(); else play(); }

    public void seek(double ms) { pos = Math.max(0, Math.min(length(), ms)); }
    public void seekFraction(double f) { seek(f * length()); }
    public double fraction() { return length() <= 0 ? 0 : pos / length(); }

    /** Moves on by real elapsed ms when playing; returns true when the position changed. Stops at the end. */
    public boolean advance(long realMs) {
        if (!playing) return false;
        pos += realMs * speed;
        if (pos >= length()) {
            pos = length();
            playing = false;
        }
        return true;
    }

    /** Index of the last sample at or before ms. */
    public int indexAt(double ms) {
        double target = ms + (log.count() > 0 ? log.t[0] : 0);
        int lo = 0, hi = log.count() - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (log.t[mid] <= target) { ans = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return ans;
    }

    public int index() { return indexAt(pos); }

    public double rpmNow() { return log.count() == 0 ? Double.NaN : log.rpm[index()]; }
    public double mapNow() { return log.count() == 0 ? Double.NaN : log.map[index()]; }
    public double rowNow() { return table == null ? Double.NaN : table.rowPos(rpmNow()); }
    public double colNow() { return table == null ? Double.NaN : table.colPos(mapNow()); }

    /** The trail behind the dot, oldest first: [0] rows, [1] cols, [2] opacity 0..1 (oldest faintest). Empty if no table. */
    public double[][] trail() {
        if (table == null || log.count() == 0) return new double[][]{new double[0], new double[0], new double[0]};
        int last = index();
        double since = log.t[last] - TRAIL_MS * speed;
        int first = last;
        while (first > 0 && log.t[first - 1] >= since) first--;
        int n = last - first + 1;
        int stride = Math.max(1, (int) Math.ceil(n / (double) TRAIL_POINTS));
        int m = 0;
        double[] r = new double[n / stride + 1], c = new double[r.length], a = new double[r.length];
        for (int i = last; i >= first; i -= stride) {
            double age = (log.t[last] - log.t[i]) / (TRAIL_MS * speed);
            r[m] = table.rowPos(log.rpm[i]);
            c[m] = table.colPos(log.map[i]);
            a[m] = Math.max(0.05, 1 - age);
            m++;
        }
        double[][] out = {new double[m], new double[m], new double[m]};
        for (int i = 0; i < m; i++) {      // reverse to oldest first
            out[0][i] = r[m - 1 - i];
            out[1][i] = c[m - 1 - i];
            out[2][i] = a[m - 1 - i];
        }
        return out;
    }

    public static String clock(double ms) {
        long s = Math.round(ms / 1000);
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
