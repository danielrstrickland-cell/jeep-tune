package com.strickland.jeeptune.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds the short summary.txt that is saved next to flash.bin and log.txt. */
public final class Summary {
    private Summary() { }

    private static final Pattern MS = Pattern.compile("^\\S+ TX .* RX .* (\\d+) ms$");

    public static String build(ReadJob job, JobStore store) throws IOException {
        job.flush();
        List<String> log = new ArrayList<>();
        File lf = new File(job.dir, "log.txt");
        if (lf.exists()) log.addAll(Files.readAllLines(lf.toPath(), StandardCharsets.UTF_8));
        List<String> probes = new ArrayList<>(), sessions = new ArrayList<>(), stops = new ArrayList<>();
        List<Long> ms = new ArrayList<>();
        String format = "not confirmed";
        for (String l : log) {
            int i;
            if (l.contains(" PROBE TX ")) probes.add(l.substring(l.indexOf("PROBE")).replaceAll(" \\d+ ms$", ""));
            else if ((i = l.indexOf(" session ")) >= 0 && l.contains(" timing: ")) sessions.add(l.substring(i + 1));
            else if ((i = l.indexOf(" STOP ")) >= 0) stops.add(l.substring(i + 6));
            else if ((i = l.indexOf("format confirmed: ")) >= 0) format = l.substring(i + 18).trim();
            else {
                Matcher m = MS.matcher(l);
                if (m.matches()) ms.add(Long.parseLong(m.group(1)));
            }
        }
        boolean full = job.size == ReadJob.FULL;
        StringBuilder b = new StringBuilder();
        b.append("Jeep Tune summary, job ").append(job.id).append('\n');
        b.append("Kind: ").append(full ? "Full read 000000-03FFFF" : "1 KB test 000000-0003FF").append('\n');
        b.append("Bytes read: ").append(job.next).append(" of ").append(job.size).append(" (")
                .append(Fmt.percent(job.next, job.size)).append("%)\n");
        b.append("Sessions: ").append(job.sessions).append("   Failed requests (retries): ").append(job.retries).append('\n');
        if (ms.isEmpty()) b.append("Read time: no data\n");
        else {
            long[] a = new long[ms.size()];
            long sum = 0;
            for (int i = 0; i < a.length; i++) { a[i] = ms.get(i); sum += a[i]; }
            Arrays.sort(a);
            long med = a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2;
            b.append("Read time per request: avg ").append(Math.round(sum / (double) a.length)).append(" ms, median ")
                    .append(med).append(" ms (").append(a.length).append(" requests)\n");
        }
        b.append("Timing per session:\n");
        if (sessions.isEmpty()) b.append("  none logged\n");
        for (String s : sessions) b.append("  ").append(s).append('\n');
        b.append("Format: ").append(format).append('\n');
        b.append("Probe lines (last 6):\n");
        if (probes.isEmpty()) b.append("  none\n");
        for (int i = Math.max(0, probes.size() - 6); i < probes.size(); i++) b.append("  ").append(probes.get(i)).append('\n');
        b.append("Stop messages:\n");
        if (stops.isEmpty()) b.append("  none\n");
        for (String s : stops) b.append("  ").append(s).append('\n');
        b.append("Compare: ").append(compare(job, store)).append('\n');
        b.append("SHA-256 of flash.bin: ").append(sha256(new File(job.dir, "flash.bin"))).append('\n');
        return b.toString();
    }

    private static String compare(ReadJob job, JobStore store) {
        try {
            if (job.compareTo.isEmpty()) {
                return job.verifiedOf.isEmpty() ? "not a compare read" : "verified yes (matches " + job.verifiedOf + ")";
            }
            ReadJob a = store.load(job.compareTo);
            if (!job.complete() || !a.complete() || a.size != job.size) return "not finished (against " + a.id + ")";
            int n = 0;
            List<String> first = new ArrayList<>();
            for (int i = 0; i < a.size; i++) {
                if (a.data[i] != job.data[i]) {
                    n++;
                    if (first.size() < 10) first.add(String.format("%06X", i));
                }
            }
            return "against " + a.id + ", verified " + (n == 0 ? "yes" : "no") + ", mismatches " + n
                    + (n == 0 ? "" : ", first " + String.join(" ", first));
        } catch (IOException | RuntimeException e) {
            return "unavailable (" + e.getMessage() + ")";
        }
    }

    public static String sha256(File f) throws IOException {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] h = d.digest(Files.readAllBytes(f.toPath()));
            StringBuilder s = new StringBuilder();
            for (byte x : h) s.append(String.format("%02x", x & 0xff));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
