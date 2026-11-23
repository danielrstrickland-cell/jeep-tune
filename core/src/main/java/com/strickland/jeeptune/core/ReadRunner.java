package com.strickland.jeeptune.core;

import java.io.IOException;

/** Runs one read job address by address, in order, with the stop rules. */
public final class ReadRunner {

    public enum Stop {
        COMPLETE("Read complete."),
        PAUSED("Paused and saved."),
        NOT_READY("Not started: the engine computer did not answer."),
        ENGINE_RUNNING("Stopped and saved: engine speed is above 0."),
        LOW_BATTERY("Stopped and saved: battery below 12.0 V."),
        NO_ANSWER("Stopped and saved: 5 requests in a row got no answer."),
        RESUME_MISMATCH("Cannot resume: computer data changed or different computer. Start a new read."),
        UNKNOWN_FORMAT("Unknown answer format. Tap Save .bin + log and send the log."),
        ERROR("Stopped and saved: dongle link lost.");

        public final String message;
        Stop(String m) { message = m; }
    }

    public static final int CHECK_EVERY = 256, MAX_FAILS = 5, RESUME_BYTES = 16;

    private final Ecu ecu;
    private volatile boolean stopRequested;
    private int fails;
    private Ecu.Shape shape;
    static final int[] PROBE_ADDRS = {0x012345, 0x02468A, 0x013579};
    /** Test hook: pause after this many reads in a run (-1 = off). */
    int requestStopAfterForTest = -1;
    private int readsThisRun;

    public ReadRunner(Ecu ecu) { this.ecu = ecu; }

    public void requestStop() { stopRequested = true; }

    public Stop run(ReadJob job) {
        Stop s = runInner(job);
        try {
            job.log(ts() + " STOP " + s.message, "STOP " + s.message);
            job.flush();
        } catch (IOException ignored) { }
        return s;
    }

    private Stop runInner(ReadJob job) {
        stopRequested = false;
        fails = 0;
        readsThisRun = 0;
        try {
            try {
                Stop s = start(job);
                if (s != null) return s;
                return loop(job);
            } finally {
                job.flush();
            }
        } catch (IOException e) {
            try { job.flush(); } catch (IOException ignored) { }
            return Stop.ERROR;
        }
    }

    private Stop start(ReadJob job) throws IOException {
        ecu.setup(job.timing);
        Ecu.Check c = ecu.check();
        Stop s = judge(c);
        if (s != null) return s == Stop.NO_ANSWER ? Stop.NOT_READY : s;
        Stop f = probe(job);
        if (f != null) return f;
        if (job.next > 0) {
            int n = Math.min(RESUME_BYTES, job.next);
            for (int i = 0; i < n; i++) {
                int addr = job.next - n + i;
                Ecu.Reply r = readAddr(job, addr);
                if (r == null) { job.resumeCheck = "no answer"; return Stop.NO_ANSWER; }
                if ((r.value & 0xff) != (job.data[addr] & 0xff)) {
                    job.resumeCheck = "FAILED: byte at " + String.format("%06X", addr) + " differs";
                    return Stop.RESUME_MISMATCH;
                }
            }
            job.resumeCheck = "passed (last " + n + " saved bytes re-read, all match)";
        } else {
            job.resumeCheck = "";
        }
        job.sessions++;
        job.log(ts() + " session " + job.sessions + " timing: " + job.timing.key + " " + job.timing.ms + " ms",
                "session " + job.sessions + " timing: " + job.timing.key + " " + job.timing.ms + " ms");
        job.flush();
        return null;
    }

    /** Format check before any byte of this session is stored. Probe values are only logged. */
    private Stop probe(ReadJob job) throws IOException {
        shape = null;
        java.util.EnumSet<Ecu.Shape> common = java.util.EnumSet.allOf(Ecu.Shape.class);
        for (int addr : PROBE_ADDRS) {
            byte[] got = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                Ecu.Reply r = ecu.request(addr);
                String line = "PROBE TX " + r.tx + " -> " + (r.data == null ? "no answer" : "RX " + r.rx);
                job.log(ts() + " " + line + " " + r.ms + " ms", line);
                if (r.data != null) { got = r.data; break; }
            }
            for (Ecu.Shape sh : Ecu.Shape.values()) if (!Ecu.fits(got, addr, sh)) common.remove(sh);
        }
        Ecu.Shape picked = common.contains(Ecu.Shape.C) ? Ecu.Shape.C : common.size() == 1 ? common.iterator().next() : null;
        boolean allFit = picked != null;
        if (!allFit) {
            job.log(ts() + " format NOT confirmed", "format NOT confirmed");
            return Stop.UNKNOWN_FORMAT;
        }
        shape = picked;
        job.log(ts() + " format confirmed: " + picked, "format confirmed: " + picked);
        return null;
    }

    private static String ts() {
        return new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date());
    }

    private Stop judge(Ecu.Check c) {
        if (!c.answering) return Stop.NO_ANSWER;
        if (c.rpm > 0) return Stop.ENGINE_RUNNING;
        if (c.volts < Gate.MIN_VOLTS) return Stop.LOW_BATTERY;
        return null;
    }

    private Stop loop(ReadJob job) throws IOException {
        int sinceCheck = 0;
        while (job.next < job.size) {
            if (stopRequested) return Stop.PAUSED;
            if (sinceCheck >= CHECK_EVERY) {
                Ecu.Check c = ecu.check();
                if (!c.answering) {
                    if (++fails >= MAX_FAILS) return Stop.NO_ANSWER;
                    continue;
                }
                Stop s = judge(c);
                if (s != null) return s;
                fails = 0;
                sinceCheck = 0;
            }
            Ecu.Reply r = readAddr(job, job.next);
            if (r == null) return Stop.NO_ANSWER;
            job.advance(r.value);
            job.recordMs(r.ms);
            sinceCheck++;
            if (requestStopAfterForTest >= 0 && ++readsThisRun >= requestStopAfterForTest) stopRequested = true;
            if (job.next % ReadJob.FLUSH_EVERY == 0) job.flush();
        }
        return Stop.COMPLETE;
    }

    /** One address, retried until it answers; null after 5 failed requests in a row. */
    private Ecu.Reply readAddr(ReadJob job, int addr) throws IOException {
        while (true) {
            Ecu.Reply r = ecu.readByte(addr, shape);
            long now = System.currentTimeMillis();
            String ts = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date(now));
            if (r.ok) {
                fails = 0;
                job.log(ts + " TX " + r.tx + " RX " + r.rx + " " + r.ms + " ms",
                        "TX " + r.tx + "  →  RX " + r.rx + "   " + r.ms + " ms");
                return r;
            }
            job.retries++;
            fails++;
            job.log(ts + " TX " + r.tx + " RX " + r.rx + " " + r.ms + " ms",
                    "TX " + r.tx + "  →  " + (r.rx.equals("no answer") ? "no answer" : "bad answer " + r.rx) + ", retry " + fails);
            if (fails >= MAX_FAILS) return null;
        }
    }
}
