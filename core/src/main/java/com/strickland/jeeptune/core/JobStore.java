package com.strickland.jeeptune.core;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** All jobs under one folder (reads/ in the app files). */
public final class JobStore {
    private final File root;

    public JobStore(File root) { this.root = root; }

    public ReadJob create(int size, Timing timing, String compareTo) throws IOException {
        String base = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + (size >= ReadJob.FULL ? "-256k" : "-1k");
        String id = base;
        for (int i = 2; new File(root, id).exists(); i++) id = base + "-" + i;
        return ReadJob.create(root, id, size, timing, compareTo);
    }

    public ReadJob load(String id) throws IOException { return ReadJob.load(new File(root, id)); }

    /** Every readable job, oldest first. Broken folders are skipped. */
    public List<ReadJob> all() {
        List<ReadJob> out = new ArrayList<>();
        File[] ds = root.listFiles();
        if (ds != null) {
            for (File d : ds) {
                try {
                    out.add(ReadJob.load(d));
                } catch (IOException | RuntimeException ignored) { }
            }
        }
        out.sort((a, b) -> a.createdAt != b.createdAt ? Long.compare(a.createdAt, b.createdAt) : a.id.compareTo(b.id));
        return out;
    }

    /** The newest job, whatever state it is in. */
    public ReadJob latest() {
        List<ReadJob> a = all();
        return a.isEmpty() ? null : a.get(a.size() - 1);
    }

    public static final class Compared {
        public int mismatches;
        public final List<Integer> first = new ArrayList<>();
        public boolean verified;
        public String error;
    }

    /** Compares a finished second read with the first one it points to; equal marks both verified. */
    public Compared compare(ReadJob second) throws IOException {
        Compared c = new Compared();
        if (second.compareTo.isEmpty() || !second.complete()) { c.error = "second read not complete"; return c; }
        ReadJob a = load(second.compareTo);
        if (!a.complete() || a.size != second.size) { c.error = "first read missing or incomplete"; return c; }
        for (int i = 0; i < a.size; i++) {
            if (a.data[i] != second.data[i]) {
                c.mismatches++;
                if (c.first.size() < 10) c.first.add(i);
            }
        }
        if (c.mismatches == 0) {
            c.verified = true;
            a.verifiedOf = second.id;
            second.verifiedOf = a.id;
            a.writeProgress();
            second.writeProgress();
        }
        return c;
    }
}
