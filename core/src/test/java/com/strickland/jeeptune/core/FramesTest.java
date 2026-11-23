package com.strickland.jeeptune.core;

public final class FramesTest {
    private FramesTest() { }

    private static void refused(String hex) {
        T.ok(!Frames.allowed(Hex.bytes(hex)), "should be refused: " + hex);
        try {
            Frames.check(Hex.bytes(hex));
            throw new AssertionError("check did not throw for " + hex);
        } catch (Frames.Refused expected) { }
    }

    public static void allowsExactlyTheListedFrames() {
        String[] ok = {"60 05 19 01", "60 06 00", "60 01 49 c4 21", "60 02 00 00 1e 84 00 08",
                "60 03 00 78 07 d0 00 14 00 05", "60 03 00 1e 07 d0 00 14 00 05", "60 03 00 0a 07 d0 00 14 00 05",
                "60 02 00 00 f4 24 00 08 ff", "60 02 00 00 1e 84 00 08 ff",
                "61 04 00 03 f4 0a 0b 01", "61 04 00 02 f4 0f 01",
                "61 04 00 04 26 00 00 00 01", "61 04 00 04 26 03 ff ff 01"};
        for (String s : ok) T.ok(Frames.allowed(Hex.bytes(s)), "should be allowed: " + s);
        T.eq("61 04 00 04 26 01 02 03 01", Hex.str(Frames.read(0x010203)), "read frame");
    }

    public static void refusesWritesErasesResetsAndOthers() {
        refused("61 04 00 01 17 01");              // erase codes
        refused("61 04 00 02 23 01 01 01");        // erase all fault data
        refused("61 04 00 02 23 02 01");           // reset learned values
        refused("61 04 00 02 23 03 01");
        refused("61 04 00 05 1c 00 00 00 aa 01");  // write memory
        refused("61 04 00 02 13 01 01");           // actuator test
        refused("61 04 00 02 19 20 01");           // set idle speed
        refused("61 04 00 01 10 01");              // stored codes (not on this screen's list)
        refused("61 04 00 05 26 00 00 00 aa 01");  // 26 with wrong length
        refused("61 04 00 03 26 00 00 01");
        refused("61 04 00 04 26 00 00 00 00");     // missing flag
        refused("61 04 00 04 1c 00 00 00 01");     // 4 bytes but not a 26
        refused("61 04 00 02 f4 0a 01");           // other fast reads
        refused("17");
        refused("23 01");
        refused("1c 00 00 00 aa");
        refused("13 01");
        refused("19 20");
        refused("");
        refused("60 03 00 0a 07 d0 00 14 00 05 00");
        refused("60 03 00 0a 07 d0 00 14 00 06");
        refused("60 03 00 0a 07 d0 00 14 00 00");
        refused("60 03 00 05 07 d0 00 14 00 05");
        refused("60 03 00 78 07 d0 00 14 00 06");  // altered config
        refused("60 07 00");
    }

    public static void ecuRefusesBeforeAnythingIsSent() {
        FakeEcu e = new FakeEcu(new byte[16]);
        Ecu ecu = new Ecu(FakeEcu.attach(e));
        try {
            Frames.fixed("61 04 00 01 17 01");
            throw new AssertionError("fixed() accepted an erase");
        } catch (Frames.Refused expected) { }
        try {
            Frames.read(0x1000000);
            throw new AssertionError("address out of range accepted");
        } catch (Frames.Refused expected) { }
        T.eq(0, e.sent.size(), "nothing sent");
        T.ok(ecu != null, "ecu");
    }
}
