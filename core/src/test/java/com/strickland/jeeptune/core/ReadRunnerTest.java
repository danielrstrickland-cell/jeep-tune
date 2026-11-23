package com.strickland.jeeptune.core;

import java.io.File;
import java.nio.file.Files;
import java.util.Random;

public final class ReadRunnerTest {
    private ReadRunnerTest() { }

    private static byte[] rom(int n, long seed) {
        byte[] r = new byte[n];
        new Random(seed).nextBytes(r);
        return r;
    }

    private static JobStore store() throws Exception {
        return new JobStore(Files.createTempDirectory("tune").toFile());
    }

    private static void onlyAllowed(FakeEcu e) {
        for (String s : e.sent) T.ok(Frames.allowed(Hex.bytes(s)), "sent a frame not on the list: " + s);
    }

    public static void readsOneKbInOrderAndSaves() throws Exception {
        FakeEcu e = new FakeEcu(rom(ReadJob.FULL, 1));
        ReadRunner rr = new ReadRunner(new Ecu(FakeEcu.attach(e)));
        ReadJob j = store().create(ReadJob.KB1, false, null);
        T.eq(ReadRunner.Stop.COMPLETE, rr.run(j), "outcome");
        for (int i = 0; i < ReadJob.KB1; i++) T.eq(e.rom[i], j.data[i], "byte " + i);
        ReadJob back = new JobStore(j.dir.getParentFile()).load(j.id);
        T.eq(ReadJob.KB1, back.next, "saved next");
        T.eq(1, back.sessions, "sessions");
        T.eq(0, back.retries, "retries");
        for (int i = 0; i < ReadJob.KB1; i++) T.eq(e.rom[i], back.data[i], "saved byte " + i);
        T.ok(new File(j.dir, "log.txt").length() > 0, "log written");
        onlyAllowed(e);
        // addresses requested strictly in order
        int prev = -1, seen = 0;
        for (String s : e.sent) {
            if (s.startsWith("61 04 00 04 26") && ++seen > 3) {   // first three are the format probe
                String[] p = s.split(" ");
                int a = (Integer.parseInt(p[5], 16) << 16) | (Integer.parseInt(p[6], 16) << 8) | Integer.parseInt(p[7], 16);
                T.ok(a == prev + 1, "in order at " + a);
                prev = a;
            }
        }
    }

    public static void acceptsAnswerWithoutEchoPrefixButNotWrongAddress() throws Exception {
        FakeEcu e = new FakeEcu(rom(ReadJob.KB1, 2));
        e.echoPrefix26 = false;
        ReadJob j = store().create(ReadJob.KB1, false, null);
        T.eq(ReadRunner.Stop.COMPLETE, new ReadRunner(new Ecu(FakeEcu.attach(e))).run(j), "no-prefix answers");
        FakeEcu w = new FakeEcu(rom(ReadJob.KB1, 2));
        w.wrongEcho = true;
        w.wrongEchoAfterReads = 3;   // probe passes, then every answer has the wrong address
        ReadJob j2 = store().create(ReadJob.KB1, false, null);
        T.eq(ReadRunner.Stop.NO_ANSWER, new ReadRunner(new Ecu(FakeEcu.attach(w))).run(j2), "wrong address never accepted");
        T.eq(0, j2.next, "nothing stored");
        T.eq(5, j2.retries, "five failed requests");
    }

    private static void unknownFormat(FakeEcu e, String what) throws Exception {
        ReadJob j = store().create(ReadJob.KB1, false, null);
        T.eq(ReadRunner.Stop.UNKNOWN_FORMAT, new ReadRunner(new Ecu(FakeEcu.attach(e))).run(j), what);
        T.eq("Unknown answer format. Tap Save .bin + log and send the log.", ReadRunner.Stop.UNKNOWN_FORMAT.message, "message");
        T.eq(0, j.next, "nothing stored: " + what);
        for (byte b : j.data) T.eq((byte) 0, b, "data untouched: " + what);
        T.ok(new File(j.dir, "log.txt").length() > 0, "probe logged: " + what);
        onlyAllowed(e);
    }

    public static void shapeAConfirmedAndAddressZeroStored() throws Exception {
        FakeEcu e = new FakeEcu(rom(ReadJob.KB1, 11));
        e.rom[0] = 0x0f;
        ReadJob j = store().create(ReadJob.KB1, false, null);
        T.eq(ReadRunner.Stop.COMPLETE, new ReadRunner(new Ecu(FakeEcu.attach(e))).run(j), "outcome");
        T.eq((byte) 0x0f, j.data[0], "00 00 00 00 0f stored as 0x0f");
        String log = new String(Files.readAllBytes(new File(j.dir, "log.txt").toPath()));
        T.ok(log.contains("PROBE TX 26 01 23 45 -> RX 00 01 23 45 "), "probe logged");
        T.ok(log.contains("format confirmed: A"), "confirmed A");
        T.ok(e.sent.get(e.sent.size() - 1) != null, "sent");
    }

    public static void shapeAWith26PrefixAccepted() throws Exception {
        FakeEcu e = new FakeEcu(rom(ReadJob.KB1, 12));
        e.prefixByte = 0x26;
        ReadJob j = store().create(ReadJob.KB1, false, null);
        T.eq(ReadRunner.Stop.COMPLETE, new ReadRunner(new Ecu(FakeEcu.attach(e))).run(j), "26 01 23 45 7a style");
        T.eq(e.rom[5], j.data[5], "value");
        T.eq(Ecu.Shape.A, Ecu.shapeOf(Hex.bytes("26 01 23 45 7a"), 0x012345), "shape");
    }

    private static byte[] realAnswer(int addr) {
        switch (addr) {
            case 0x012345: return Hex.bytes("23 23 45 45 80");
            case 0x02468A: return Hex.bytes("46 46 8a 8a 17");
            case 0x013579: return Hex.bytes("35 35 79 79 19");
            case 0: return Hex.bytes("00 00 00 00 0f");
            default: return null;
        }
    }

    public static void shapeCFromRealCarConfirmedAndUsed() throws Exception {
        FakeEcu e = new FakeEcu(rom(ReadJob.KB1, 16));
        e.override = ReadRunnerTest::realAnswer;
        ReadJob j = store().create(ReadJob.KB1, false, null);
        ReadRunner rr = new ReadRunner(new Ecu(FakeEcu.attach(e)));
        rr.requestStopAfterForTest = 1;
        T.eq(ReadRunner.Stop.PAUSED, rr.run(j), "paused after first byte");
        T.eq((byte) 0x0f, j.data[0], "000000 stored as 0x0f");
        String log = new String(Files.readAllBytes(new File(j.dir, "log.txt").toPath()));
        T.ok(log.contains("format confirmed: C"), "confirmed C");
        Ecu ecu = new Ecu(FakeEcu.attach(e));
        Ecu.Reply r = ecu.readByte(0x012345, Ecu.Shape.C);
        T.ok(r.ok && r.value == 0x80, "012345 gives 0x80");
        e.override = ad -> Hex.bytes("24 23 45 45 80");   // wrong MM
        T.ok(!ecu.readByte(0x012345, Ecu.Shape.C).ok, "wrong MM rejected");
        e.override = ad -> Hex.bytes("23 23 45 46 80");   // wrong LL
        T.ok(!ecu.readByte(0x012345, Ecu.Shape.C).ok, "wrong LL rejected");
        FakeEcu bad = new FakeEcu(rom(ReadJob.KB1, 16));
        bad.override = ad -> ad == 0x02468A ? Hex.bytes("46 47 8a 8a 17") : realAnswer(ad);
        unknownFormat(bad, "C with wrong MM at a probe");
    }

    public static void shapeBConfirmedAndUsed() throws Exception {
        FakeEcu e = new FakeEcu(rom(ReadJob.KB1, 13));
        e.echoPrefix26 = false;
        ReadJob j = store().create(ReadJob.KB1, false, null);
        T.eq(ReadRunner.Stop.COMPLETE, new ReadRunner(new Ecu(FakeEcu.attach(e))).run(j), "outcome");
        for (int i = 0; i < ReadJob.KB1; i++) T.eq(e.rom[i], j.data[i], "byte " + i);
        String log = new String(Files.readAllBytes(new File(j.dir, "log.txt").toPath()));
        T.ok(log.contains("format confirmed: B"), "confirmed B");
    }

    public static void unknownOrMixedProbeAnswersStopAndStoreNothing() throws Exception {
        FakeEcu a = new FakeEcu(rom(ReadJob.KB1, 14));
        a.override = ad -> new byte[]{1, 0x23, 0x45, 0, 0x7a};            // 01 23 45 00 7a
        unknownFormat(a, "address in wrong place");
        FakeEcu w = new FakeEcu(rom(ReadJob.KB1, 14));
        w.wrongEcho = true;
        unknownFormat(w, "wrong address");
        FakeEcu m = new FakeEcu(rom(ReadJob.KB1, 14));
        m.override = ad -> ad == 0x02468A ? new byte[]{1, 2, 3, 4} : null;   // 3rd and 1st differ in shape
        m.override = ad -> ad == 0x02468A ? new byte[]{0x02, 0x46, (byte) 0x8A, 9} : null;
        unknownFormat(m, "mixed A and B");
        FakeEcu n = new FakeEcu(rom(ReadJob.KB1, 14));
        n.answering = true;
        n.override = ad -> ad == 0x013579 ? new byte[]{0, 0} : null;
        unknownFormat(n, "odd length");
    }

    public static void probeRetriesNoAnswerUpToThreeTimes() throws Exception {
        FakeEcu e = new FakeEcu(rom(ReadJob.KB1, 15));
        e.failAt.put(0x012345, 2);
        ReadJob j = store().create(ReadJob.KB1, false, null);
        T.eq(ReadRunner.Stop.COMPLETE, new ReadRunner(new Ecu(FakeEcu.attach(e))).run(j), "two misses then answer");
        FakeEcu f = new FakeEcu(rom(ReadJob.KB1, 15));
        f.failAt.put(0x012345, 3);
        unknownFormat(f, "three misses");
    }

    public static void retriesAFailedAddressAndCounts() throws Exception {
        FakeEcu e = new FakeEcu(rom(ReadJob.KB1, 3));
        e.failAt.put(10, 3);
        ReadJob j = store().create(ReadJob.KB1, false, null);
        T.eq(ReadRunner.Stop.COMPLETE, new ReadRunner(new Ecu(FakeEcu.attach(e))).run(j), "outcome");
        T.eq(3, j.retries, "retries");
        T.eq(e.rom[10], j.data[10], "byte 10 after retries");
    }

    public static void stopsAfterFiveFailuresInARowAndResumes() throws Exception {
        FakeEcu e = new FakeEcu(rom(ReadJob.KB1, 4));
        JobStore st = store();
        ReadJob j = st.create(ReadJob.KB1, false, null);
        ReadRunner rr = new ReadRunner(new Ecu(FakeEcu.attach(e)));
        e.failAt.put(100, 5);
        T.eq(ReadRunner.Stop.NO_ANSWER, rr.run(j), "auto-stop");
        T.eq(100, j.next, "stopped at 100");
        T.eq(100, st.load(j.id).next, "state saved on auto-stop");
        // resume with a fresh link
        ReadJob again = st.load(j.id);
        FakeEcu e2 = new FakeEcu(e.rom);
        T.eq(ReadRunner.Stop.COMPLETE, new ReadRunner(new Ecu(FakeEcu.attach(e2))).run(again), "resumed");
        T.eq(2, again.sessions, "two sessions");
        T.ok(again.resumeCheck.startsWith("passed"), "resume check: " + again.resumeCheck);
        for (int i = 0; i < ReadJob.KB1; i++) T.eq(e.rom[i], again.data[i], "byte " + i);
        onlyAllowed(e2);
    }

    public static void resumeRefusedWhenComputerDataDiffers() throws Exception {
        byte[] a = rom(ReadJob.KB1, 5);
        FakeEcu e = new FakeEcu(a);
        JobStore st = store();
        ReadJob j = st.create(ReadJob.KB1, false, null);
        e.failAt.put(200, 5);
        new ReadRunner(new Ecu(FakeEcu.attach(e))).run(j);
        byte[] b = a.clone();
        b[190] ^= 0x55;
        FakeEcu other = new FakeEcu(b);
        ReadJob again = st.load(j.id);
        T.eq(ReadRunner.Stop.RESUME_MISMATCH, new ReadRunner(new Ecu(FakeEcu.attach(other))).run(again), "refused");
        T.eq(200, again.next, "nothing mixed in");
        T.eq(1, again.sessions, "refusal is not a session");
    }

    public static void stopsWhenEngineStartsOrBatteryDrops() throws Exception {
        FakeEcu e = new FakeEcu(rom(ReadJob.KB1, 6));
        e.rpmAfterReads = 300;
        ReadJob j = store().create(ReadJob.KB1, false, null);
        T.eq(ReadRunner.Stop.ENGINE_RUNNING, new ReadRunner(new Ecu(FakeEcu.attach(e))).run(j), "engine");
        T.eq(512, j.next, "stops at the next 256-byte check");
        FakeEcu b = new FakeEcu(rom(ReadJob.KB1, 6));
        b.volts = 11.9;
        ReadJob j2 = store().create(ReadJob.KB1, false, null);
        T.eq(ReadRunner.Stop.LOW_BATTERY, new ReadRunner(new Ecu(FakeEcu.attach(b))).run(j2), "battery at start");
        T.eq(0, j2.next, "nothing read");
        FakeEcu n = new FakeEcu(rom(ReadJob.KB1, 6));
        n.answering = false;
        ReadJob j3 = store().create(ReadJob.KB1, false, null);
        T.eq(ReadRunner.Stop.NOT_READY, new ReadRunner(new Ecu(FakeEcu.attach(n))).run(j3), "not answering");
    }

    public static void stopRequestSavesAndFastTimingIsSent() throws Exception {
        FakeEcu e = new FakeEcu(rom(ReadJob.KB1, 7));
        ReadJob j = store().create(ReadJob.KB1, true, null);
        ReadRunner rr = new ReadRunner(new Ecu(FakeEcu.attach(e)));
        rr.requestStopAfterForTest = 70;
        T.eq(ReadRunner.Stop.PAUSED, rr.run(j), "paused");
        T.eq(70, j.next, "saved position");
        T.ok(e.sent.contains(Frames.TIMING_FAST), "fast timing sent");
        T.ok(!e.sent.contains(Frames.TIMING_NORMAL), "normal timing not sent");
    }

    public static void verifiedCopyAndMismatchReport() throws Exception {
        byte[] a = rom(ReadJob.KB1, 8);
        JobStore st = store();
        ReadJob first = st.create(ReadJob.KB1, false, null);
        new ReadRunner(new Ecu(FakeEcu.attach(new FakeEcu(a)))).run(first);
        ReadJob second = st.create(ReadJob.KB1, false, first.id);
        new ReadRunner(new Ecu(FakeEcu.attach(new FakeEcu(a)))).run(second);
        JobStore.Compared c = st.compare(second);
        T.ok(c.verified && c.mismatches == 0, "verified");
        T.eq(second.id, st.load(first.id).verifiedOf, "first marked");
        T.eq(first.id, st.load(second.id).verifiedOf, "second marked");
        byte[] b = a.clone();
        b[5] ^= 1; b[900] ^= 1;
        ReadJob third = st.create(ReadJob.KB1, false, first.id);
        new ReadRunner(new Ecu(FakeEcu.attach(new FakeEcu(b)))).run(third);
        JobStore.Compared d = st.compare(third);
        T.ok(!d.verified, "not verified");
        T.eq(2, d.mismatches, "count");
        T.eq(5, d.first.get(0), "first address");
        T.eq(900, d.first.get(1), "second address");
    }

    public static void formatting() {
        T.eq("≈ 13 h 16 m", Fmt.timeLeft(223224, 214), "time left");
        T.eq("15%", Fmt.percent(38920, 262144) + "%", "percent");
        T.eq("262,144", Fmt.count(262144), "grouped");
        byte[] d = new byte[64];
        d[0] = 'A'; d[1] = 0;
        String row = Fmt.row(d, 0, 2);
        T.ok(row.startsWith("000000  41 00 -- -- -- -- -- --  -- --"), "row: " + row);
        T.ok(row.endsWith("A."), "ascii: " + row);
        T.eq(4, Fmt.rows(d, 20, 4).split("\n").length, "row count");
    }

    public static void startGate() {
        Ecu.Check c = new Ecu.Check();
        c.answering = true; c.rpm = 0; c.volts = 12.6;
        T.eq(null, Gate.blocker(true, c, true), "all met");
        T.ok(Gate.blocker(false, c, true) != null, "no dongle");
        T.ok(Gate.blocker(true, c, false) != null, "no charger");
        c.volts = 11.99;
        T.ok(Gate.blocker(true, c, true) != null, "low battery");
        c.volts = 12.0; c.rpm = 750;
        T.ok(Gate.blocker(true, c, true) != null, "engine");
        c.rpm = 0; c.answering = false;
        T.ok(Gate.blocker(true, c, true) != null, "not answering");
    }

    public static void releaseTags() {
        T.eq(7, ReleaseTag.build("tune-7"), "build");
        T.ok(ReleaseTag.isNewer("tune-8", 7), "newer");
        T.ok(!ReleaseTag.isNewer("dash-9", 7), "other app");
    }
}
