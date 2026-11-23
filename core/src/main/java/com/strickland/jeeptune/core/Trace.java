package com.strickland.jeeptune.core;

import java.util.ArrayDeque;

/** The last N lines of what was said to the dongle and what came back, for a shareable link log. */
public final class Trace {
    private final int max;
    private final ArrayDeque<String> lines = new ArrayDeque<>();

    public Trace(int maxLines) { this.max = Math.max(1, maxLines); }

    public synchronized void add(String line) {
        if (lines.size() >= max) lines.removeFirst();
        lines.addLast(line);
    }

    public synchronized String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }
}
