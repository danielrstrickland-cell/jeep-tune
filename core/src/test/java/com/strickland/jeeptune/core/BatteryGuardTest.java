package com.strickland.jeeptune.core;

import java.nio.file.Files;
import java.util.Random;

public final class BatteryGuardTest {
    private BatteryGuardTest() { }

    public static void guardPausesBelow12AndResumesAbove12_4() {
        BatteryGuard g = new BatteryGuard();
        T.ok(!g.update(12.6), "healthy");
        T.ok(!g.update(12.0), "exactly 12.0 is not low");
        T.ok(g.update(11.99), "just below 12.0 pauses");
        T.ok(g.update(12.2), "still paused between the limits");
        T.ok(g.update(12.4), "exactly 12.4 is not above");
        T.ok(!g.update(12.41), "above 12.4 resumes");
        T.ok(!g.update(12.1), "after resuming, 12.1 is fine again");
        T.ok(g.update(11.0), "drops again");
    }

    private static byte[] rom(int n, long seed) {
        byte[] r = new byte[n];
        new Random(seed).nextBytes(r);
        return r;
    }

    private static ReadJob job() throws Exception {
        return new JobStore(Files.createTempDirectory("tune").toFile()).create(ReadJob.KB1, Timing.NORMAL, null);
    }

    /** Fake clock: every flash read costs 100 ms; every pause sleep costs what it asks. */
    private static ReadRunner runner(FakeEcu e, long[] now) {
        ReadRunner rr = new ReadRunner(new Ecu(FakeEcu.attach(e)));
        rr.clock = () -> now[0];
        return rr;
    }

    public static void pausesOnLowBatteryAndResumesWithoutReadingWhilePaused() throws Exception {
        byte[] rom = rom(ReadJob.KB1, 11);
        FakeEcu e = new FakeEcu(rom);
        long[] now = {0};
        int[] sleeps = {0}, readsAtPause = {-1};
        e.override = addr -> {
            now[0] += 100;
            if (addr == 300) e.volts = 11.8;
            return null;
        };
        ReadRunner rr = runner(e, now);
        rr.sleeper = ms -> {
            now[0] += ms;
            if (sleeps[0] == 0) readsAtPause[0] = e.reads;
            T.ok(rr.batteryPaused, "flag set while paused");
            T.eq(readsAtPause[0], e.reads, "no flash reads while paused");
            sleeps[0]++;
            if (sleeps[0] == 3) e.volts = 12.2;   // not enough
            if (sleeps[0] == 5) e.volts = 12.5;   // resume
        };
        ReadJob j = job();
        T.eq(ReadRunner.Stop.COMPLETE, rr.run(j), "finishes after resuming");
        T.eq(5, sleeps[0], "stayed paused through 12.2 V, resumed at 12.5 V");
        T.eq(ReadJob.KB1, j.next, "all bytes");
        T.eq(java.util.Arrays.copyOf(rom, ReadJob.KB1), j.data, "data intact across the pause");
        T.ok(!rr.batteryPaused, "flag cleared");
        T.near(12.5, rr.volts, 0.04, "last voltage");
        String log = new String(Files.readAllBytes(new java.io.File(j.dir, "log.txt").toPath()));
        T.ok(log.contains("BATTERY LOW") && log.contains("BATTERY OK"), "pause and resume logged");
    }

    public static void stopWhilePausedSavesAndEngineStartEnds() throws Exception {
        FakeEcu e = new FakeEcu(rom(ReadJob.KB1, 12));
        long[] now = {0};
        e.override = addr -> { now[0] += 100; if (addr == 200) e.volts = 11.5; return null; };
        ReadRunner rr = runner(e, now);
        rr.sleeper = ms -> { now[0] += ms; rr.requestStop(); };
        ReadJob j = job();
        T.eq(ReadRunner.Stop.PAUSED, rr.run(j), "user stop while paused");
        T.ok(j.next > 200 && j.next < ReadJob.KB1, "stopped part way");
        ReadJob again = new JobStore(j.dir.getParentFile()).load(j.id);
        T.eq(j.next, again.next, "position saved");

        FakeEcu e2 = new FakeEcu(rom(ReadJob.KB1, 13));
        long[] now2 = {0};
        e2.override = addr -> { now2[0] += 100; if (addr == 200) e2.volts = 11.5; return null; };
        ReadRunner r2 = runner(e2, now2);
        r2.sleeper = ms -> { now2[0] += ms; e2.rpm = 900; };
        T.eq(ReadRunner.Stop.ENGINE_RUNNING, r2.run(job()), "engine started while paused");
    }

    public static void voltageIsCheckedEveryFiveSecondsNotOnly256Bytes() throws Exception {
        FakeEcu e = new FakeEcu(rom(ReadJob.KB1, 14));
        long[] now = {0};
        int checks = 0;
        e.override = addr -> { now[0] += 1000; return null; };   // slow reads: 1 s each
        ReadRunner rr = runner(e, now);
        T.eq(ReadRunner.Stop.COMPLETE, rr.run(job()), "complete");
        for (String s : e.sent) if (s.equals(Frames.BATTERY)) checks++;
        T.ok(checks > 100, "about one check per 5 s (got " + checks + ")");
    }

    public static void statusTextAndExportText() {
        T.eq("Reading flash 43% — 110 / 256 KB", ReadStatusText.progress(110 * 1024, 256 * 1024), "progress text");
        T.eq("Reading flash 43% — 110 / 256 KB · 12.6 V", ReadStatusText.line(110 * 1024, 256 * 1024, 12.6, false), "with volts");
        T.ok(ReadStatusText.line(110 * 1024, 256 * 1024, 11.8, true).startsWith("Paused — low battery (11.8 V)"), "paused text");
        String t = ExportText.sha256File("ab12", "jeeptune-x-flash.bin", "", "x", "y");
        T.ok(t.startsWith("ab12  jeeptune-x-flash.bin\n"), "sha256sum -c line first");
        T.ok(t.contains("Part number: unknown"), "part number placeholder");
        T.ok(ExportText.sha256File("ab", "f", "56041234AB", "x", "y").contains("Part number: 56041234AB"), "part number");
    }
}
