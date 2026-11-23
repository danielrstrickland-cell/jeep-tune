package com.strickland.jeeptune.core;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One read job on disk: flash.bin (pre-sized), progress.json, log.txt. */
public final class ReadJob {
    public static final int KB1 = 0x400, FULL = 0x40000, FLUSH_EVERY = 64;

    public final File dir;
    public final String id;
    public final int size;
    public final byte[] data;
    public volatile int next;
    public volatile int sessions, retries;
    public volatile boolean fastTiming;
    public volatile String verifiedOf = "", compareTo = "";
    public long createdAt, updatedAt;
    public volatile long lastSaveMs;
    public volatile String resumeCheck = "";
    private long savedAvgMs;
    private int flushed;
    private final long[] recent = new long[200];
    private int rn, rc;
    private RandomAccessFile bin;
    private BufferedOutputStream log;
    private final ArrayDeque<String> uiLog = new ArrayDeque<>();

    private ReadJob(File dir, String id, int size) {
        this.dir = dir;
        this.id = id;
        this.size = size;
        this.data = new byte[size];
    }

    public boolean complete() { return next >= size; }

    static ReadJob create(File root, String id, int size, boolean fast, String compareTo) throws IOException {
        File dir = new File(root, id);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
        ReadJob j = new ReadJob(dir, id, size);
        j.fastTiming = fast;
        j.compareTo = compareTo == null ? "" : compareTo;
        j.createdAt = j.updatedAt = System.currentTimeMillis();
        try (RandomAccessFile f = new RandomAccessFile(new File(dir, "flash.bin"), "rw")) { f.setLength(size); }
        j.writeProgress();
        return j;
    }

    static ReadJob load(File dir) throws IOException {
        String p = new String(Files.readAllBytes(new File(dir, "progress.json").toPath()), StandardCharsets.UTF_8);
        ReadJob j = new ReadJob(dir, str(p, "jobId"), (int) num(p, "size"));
        j.next = (int) num(p, "nextAddress");
        j.sessions = (int) num(p, "sessions");
        j.retries = (int) num(p, "retries");
        j.savedAvgMs = num(p, "avgMs");
        j.fastTiming = "fast".equals(str(p, "timing"));
        j.createdAt = num(p, "createdAt");
        j.updatedAt = num(p, "updatedAt");
        j.verifiedOf = str(p, "verifiedOf");
        j.compareTo = str(p, "compareTo");
        byte[] b = Files.readAllBytes(new File(dir, "flash.bin").toPath());
        if (b.length != j.size || j.next < 0 || j.next > j.size) throw new IOException("job files do not match");
        System.arraycopy(b, 0, j.data, 0, b.length);
        j.flushed = j.next;
        return j;
    }

    private static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static long num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    /** Stores the value for the next address and moves on. */
    public synchronized void advance(int value) {
        data[next] = (byte) value;
        next++;
    }

    public synchronized void recordMs(long ms) {
        recent[rn] = ms;
        rn = (rn + 1) % recent.length;
        if (rc < recent.length) rc++;
    }

    /** Average per read over the most recent reads (the saved figure until this session has any). */
    public synchronized long avgMs() {
        if (rc == 0) return savedAvgMs;
        long s = 0;
        for (int i = 0; i < rc; i++) s += recent[i];
        return Math.round(s / (double) rc);
    }

    public synchronized void log(String fileLine, String uiLine) throws IOException {
        if (log == null) log = new BufferedOutputStream(new FileOutputStream(new File(dir, "log.txt"), true));
        log.write((fileLine + "\n").getBytes(StandardCharsets.UTF_8));
        synchronized (uiLog) {
            if (uiLog.size() >= 4) uiLog.removeFirst();
            uiLog.addLast(uiLine);
        }
    }

    public String[] recentLog() {
        synchronized (uiLog) { return uiLog.toArray(new String[0]); }
    }

    /** Bytes first, then progress: a kill between the two loses only bytes that are re-read. */
    public synchronized void flush() throws IOException {
        if (bin == null) bin = new RandomAccessFile(new File(dir, "flash.bin"), "rw");
        if (next > flushed) {
            bin.seek(flushed);
            bin.write(data, flushed, next - flushed);
            flushed = next;
        }
        bin.getFD().sync();
        if (log != null) log.flush();
        writeProgress();
        lastSaveMs = System.currentTimeMillis();
    }

    public synchronized void writeProgress() throws IOException {
        updatedAt = System.currentTimeMillis();
        savedAvgMs = avgMs();
        String s = "{\"jobId\":\"" + id + "\",\"size\":" + size + ",\"nextAddress\":" + flushed
                + ",\"sessions\":" + sessions + ",\"retries\":" + retries + ",\"avgMs\":" + savedAvgMs
                + ",\"timing\":\"" + (fastTiming ? "fast" : "normal") + "\",\"createdAt\":" + createdAt
                + ",\"updatedAt\":" + updatedAt + ",\"verifiedOf\":\"" + verifiedOf
                + "\",\"compareTo\":\"" + compareTo + "\"}\n";
        File tmp = new File(dir, "progress.json.tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        }
        Files.move(tmp.toPath(), new File(dir, "progress.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized void close() {
        try { if (bin != null) bin.close(); } catch (IOException ignored) { }
        try { if (log != null) log.close(); } catch (IOException ignored) { }
        bin = null;
        log = null;
    }
}
