package com.strickland.jeeptune.core;

import java.util.ArrayList;
import java.util.List;

public class XtoolFrameTest {

    static final class Got { int seq; byte[] payload; boolean ok; }

    static List<Got> parse(byte[]... chunks) {
        List<Got> out = new ArrayList<>();
        XtoolFrame.Parser p = new XtoolFrame.Parser((seq, payload, ok) -> {
            Got g = new Got(); g.seq = seq; g.payload = payload; g.ok = ok; out.add(g);
        });
        for (byte[] c : chunks) p.push(c, c.length);
        return out;
    }

    public static void buildMatchesCapture() {
        T.eq("55 aa 00 01 00 05 ff fa 01 00 02 60 cb 56",
                Hex.str(XtoolFrame.build(1, Hex.bytes("60 cb"))), "seq 1, 60 cb");
        T.eq("55 aa 00 1f 00 13 ff ec 01 00 10 61 04 00 0b 08 07 df 02 01 00 00 00 00 00 00 14 58",
                Hex.str(XtoolFrame.build(31, Hex.bytes("61 04 00 0b 08 07 df 02 01 00 00 00 00 00 00 14"))),
                "seq 31, CAN request");
    }

    public static void parsesSplitAndJoinedFrames() {
        byte[] f = Hex.bytes("aa 55 00 2f 00 03 ff fc 01 ff ff d1");
        byte[][] oneByOne = new byte[f.length][];
        for (int i = 0; i < f.length; i++) oneByOne[i] = new byte[]{f[i]};
        List<Got> a = parse(oneByOne);
        T.eq(1, a.size(), "one frame from single bytes");
        T.eq(47, a.get(0).seq, "seq");
        T.eq("01 ff ff", Hex.str(a.get(0).payload), "payload");
        T.ok(a.get(0).ok, "checksum ok");

        byte[] two = Hex.bytes("12 34 56 aa 55 00 2f 00 03 ff fc 01 ff ff d1 aa 55 00 30 00 05 ff fa 00 00 02 00 00 cd");
        List<Got> b = parse(two);
        T.eq(2, b.size(), "two joined frames");
        T.eq(47, b.get(0).seq, "first seq");
        T.eq(48, b.get(1).seq, "second seq");
        T.eq("00 00 02 00 00", Hex.str(b.get(1).payload), "second payload");
        T.ok(b.get(1).ok, "second checksum ok");
    }

    public static void flagsBadChecksum() {
        List<Got> a = parse(Hex.bytes("aa 55 00 2f 00 03 ff fc 01 ff ff d2"));
        T.eq(1, a.size(), "frame still delivered");
        T.ok(!a.get(0).ok, "bad checksum flagged");
    }
}
