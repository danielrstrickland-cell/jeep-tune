package com.strickland.jeeptune.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Table decoding, compare, tune-log parsing, hit map and replay, on a small synthetic image (no real .bin anywhere). */
public class CalTest {
    static CalDef realDef() {
        try {
            File f = new File("../app/src/main/assets/defs/56041519.json");
            if (!f.exists()) f = new File("app/src/main/assets/defs/56041519.json");
            return CalDef.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new AssertionError("def asset missing: " + e);
        }
    }

    /** Part number as BCD at both flash locations (0x273F and 0x2746). */
    static void setPart(byte[] b, int... bcd) {
        for (int i = 0; i < bcd.length; i++) { b[0x273F + i] = (byte) bcd[i]; b[0x2746 + i] = (byte) bcd[i]; }
    }

    /** A 256 KB image with every def table written in its documented layout; cell values are r*9+c (low byte). */
    static byte[] image(CalDef d) {
        byte[] b = new byte[d.imageSize];
        setPart(b, 0x56, 0x04, 0x15, 0x19);
        CalDef.Axis rpm = d.axes.get("rpm17"), map = d.axes.get("map9");
        for (int i = 0; i < 17; i++) {
            int raw = (500 + i * 300) * 8;                       // 500, 800, ... rpm
            b[rpm.addr + 2 * i] = (byte) (raw >> 8);
            b[rpm.addr + 2 * i + 1] = (byte) raw;
        }
        b[map.addr] = 9;
        int[] mapX = {0x24, 0x3a, 0x4c, 0x60, 0x80, 0xa0, 0xc0, 0xe0, 0xff};
        for (int i = 0; i < 9; i++) { b[map.addr + 1 + 2 * i] = (byte) mapX[i]; b[map.addr + 2 + 2 * i] = (byte) (i * 32); }
        for (CalDef.TableDef t : d.tables) {
            int a = t.addr;
            if (t.isCurve()) {
                b[a] = (byte) (t.id.equals("idleSparkHigh") ? 0x84 : 4);
                for (int i = 0; i < 4; i++) { b[a + 1 + 2 * i] = (byte) (10 + i * 20); b[a + 2 + 2 * i] = (byte) (i == 3 && t.id.equals("idleSparkHigh") ? 0xF0 : 4 * i); }
                continue;
            }
            int rows = t.rows.equals("rpm17") ? 17 : 9, cols = t.cols.equals("map9") ? 9 : 9;
            b[a] = (byte) rows;
            b[a + 1] = (byte) cols;
            boolean word = t.kind.equals("table3d_word");
            b[a + 2] = (byte) (t.id.equals("iatRetard") ? 0xFF : 0);
            int at = a + (word ? 4 : 3);
            for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
                int v = r * 9 + c;
                if (t.id.equals("iatRetard") && r == 1 && c == 1) v = 0xFC;     // -4 signed
                if (word) { int w = 1000 + v; b[at] = (byte) (w >> 8); b[at + 1] = (byte) w; at += 2; }
                else b[at++] = (byte) v;
            }
        }
        int rl = 5300 * 8;
        b[0x3A78] = (byte) (rl >> 8);
        b[0x3A79] = (byte) rl;
        b[0x045A] = 0x01; b[0x045B] = (byte) 0xB7; b[0x045C] = (byte) 0x74; b[0x045D] = 0x00;   // 28800000
        return b;
    }

    public static void jsonReader() {
        java.util.Map<String, Object> m = MiniJson.obj(MiniJson.parse("{\"a\":[1,2.5,\"x\\n\\u0041\"],\"b\":{\"c\":true,\"d\":null},\"e\":-3e2}"));
        T.eq(3, MiniJson.arr(m.get("a")).size(), "array");
        T.eq("x\nA", MiniJson.arr(m.get("a")).get(2), "escapes");
        T.eq(-300.0, m.get("e"), "number");
        T.eq("\"a\\\"b\"", MiniJson.quote("a\"b"), "quote");
    }

    public static void defParsesAndScales() {
        CalDef d = realDef();
        T.eq("56041519AE", d.part, "part");
        T.eq(262144, d.imageSize, "size");
        T.eq(15, d.tables.size(), "tables");
        T.eq(5, d.scalars.size(), "scalars");
        T.eq(0x2868, d.table("sparkA").addr, "addr");
        CalDef.Scale s = d.table("sparkA").scale;
        T.eq("deg", s.unit, "unit");
        T.near(10, s.apply(20), 1e-9, "raw/2");
        T.eq(1, s.decimals(), "dec");
        T.near(0.0044 * 1000, d.table("fuelA").scale.apply(1000), 1e-9, "ms");
        T.eq("ms", d.table("fuelA").scale.unit, "ms unit (note text dropped)");
        T.near(-64 + 100, d.table("idleSparkPN").xScale.apply(100) + 64 - 64 + 0, 1e-9, "degC = raw-64");
        T.ok(d.table("iatRetard").scale.signed, "signed text");
        CalDef.Scale odd = d.scalars.get(3).scale;
        T.ok(!odd.parsed && odd.unit.equals("raw"), "unknown formula shows raw");
        T.ok(d.table("sparkB").uncertain() && !d.table("sparkA").uncertain(), "confidence marks");
        T.eq("Main spark B", d.table("sparkB").cleanName(), "clean name");
    }

    public static void gridAndAxes() {
        CalDef d = realDef();
        CalImage im = new CalImage(image(d), d);
        CalTable t = im.table(d.table("sparkA"));
        T.ok(t.ok(), "decoded");
        T.eq(17, t.rows, "rows");
        T.eq(9, t.cols, "cols");
        T.ok(t.hasRealAxes(), "axes known");
        T.near(500, t.rowAxis[0], 1e-9, "rpm axis");
        T.near(5300, t.rowAxis[16], 1e-9, "rpm axis end");
        T.near(0x24 / 2.428, t.colAxis[0], 1e-9, "map axis kPa");
        T.eq(0x2868 + 3 + 2 * 9 + 4, t.addr[2][4], "cell address");
        T.eq(22, t.raw[2][4], "raw");
        T.near(11.0, t.val[2][4], 1e-9, "scaled");
        T.eq(0x2868 + 3 + 153, t.end, "extent");
    }

    public static void signedUnknownAxesAndWord() {
        CalDef d = realDef();
        CalImage im = new CalImage(image(d), d);
        CalTable s = im.table(d.table("iatRetard"));
        T.ok(s.signed, "flag FF means signed");
        T.eq(-4, s.raw[1][1], "signed raw");
        T.near(-2.0, s.val[1][1], 1e-9, "signed scaled");
        T.ok(!s.hasRealAxes(), "unknown axes");
        T.near(8, s.rowAxis[8], 1e-9, "index axis");
        T.eq("index", s.rowUnit, "index unit");
        CalTable w = im.table(d.table("fuelA"));
        T.eq(2, w.cellSize, "word cell");
        T.eq(1000 + 9 + 3, w.raw[1][3], "u16 big endian");
        T.eq(0x2B8E + 4 + 2 * (9 + 3), w.addr[1][3], "word address");
        T.near((1000 + 12) * 0.0044, w.val[1][3], 1e-9, "ms");
        T.eq(2, w.dec, "ms decimals");
    }

    public static void curves() {
        CalDef d = realDef();
        CalImage im = new CalImage(image(d), d);
        CalTable c = im.table(d.table("idleSparkHigh"));
        T.ok(c.curve && c.signed, "bit7 signed");
        T.eq(4, c.cols, "n = hdr & 7F");
        T.eq(-16, c.raw[0][3], "signed y");
        T.near(-8.0, c.val[0][3], 1e-9, "deg");
        T.near(10 * 2, c.colAxis[0], 1e-9, "x = raw*2 rpm error");
        T.eq(0x27D3 + 2, c.addr[0][0], "y address");
        T.eq(0x27D3 + 1, c.xAddr[0], "x address");
        CalTable pn = im.table(d.table("idleSparkPN"));
        T.near(10 - 64, pn.colAxis[0], 1e-9, "degC");
        T.ok(!pn.signed, "unsigned");
    }

    public static void shortFileGivesMessageNotCrash() {
        CalDef d = realDef();
        CalImage im = new CalImage(new byte[1024], d);
        CalTable t = im.table(d.table("sparkA"));
        T.ok(!t.ok() && t.error.contains("Not in this file"), "error text: " + t.error);
        T.ok(im.scalar(d.scalars.get(1)).error != null, "scalar error");
        T.ok(!im.partCheck().sizeOk, "size flagged");
    }

    public static void scalarsAndPartCheck() {
        CalDef d = realDef();
        CalImage im = new CalImage(image(d), d);
        List<CalImage.Scalar> sc = im.scalars();
        T.eq("5300 rpm", sc.get(1).valueText(), "rev limit: rpm is a whole number");
        T.eq(28800000L, sc.get(3).raw, "speed constant raw");
        T.ok(sc.get(1).line().contains("stock 5300"), "stock shown");
        T.ok(sc.get(4).line().endsWith("?"), "confidence mark");
        T.ok(im.partCheck().good(), "part found");
        byte[] blank = new byte[262144];
        T.ok(!new CalImage(blank, d).partCheck().partFound, "part missing");
        T.eq("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", new CalImage(new byte[0], d).sha256(), "sha-256");
    }

    public static void diffFindsCellsScalarsAndOutsideBytes() {
        CalDef d = realDef();
        byte[] a = image(d), b = image(d);
        CalImage ref = new CalImage(a, d);
        int cell = ref.table(d.table("sparkA")).addr[3][3];
        b[cell] += 2;
        b[0x3A78] ^= 1;          // rev limit scalar
        b[0x9000] = 0x55;        // outside any known table
        b[0x9001] = 0x56;
        ImageDiff df = ImageDiff.compare(new CalImage(b, d), ref);
        T.eq(4, df.changedBytes, "bytes");
        T.eq(1, df.cells.size(), "one table cell");
        T.eq("Main spark A", df.cells.get(0).table, "cell table");
        T.near(1.0, df.cells.get(0).valNew - df.cells.get(0).valOld, 1e-9, "+1 deg");
        T.eq(1, df.outside.size(), "outside run merged");
        T.eq(0x9000, df.outside.get(0).start, "outside start");
        T.eq(0x9002, df.outside.get(0).end, "outside end");
        T.ok(df.lines(50).get(1).startsWith("Main spark A"), "line: " + df.lines(50));
        T.ok(!ImageDiff.compare(ref, ref).any(), "identical");
    }

    public static void tuneCsvHeaderAndHits() {
        String csv = "# profile=cruise\n# started=2026-11-24T22:58:00Z\n# missing=Foo\ntime_ms,RPM (rpm),Spark advance (deg),MAP (kPa)\n"
                + "0,800,10,30\n100,820,10,31\n200,2000,12,60\n300,,12,60\n400,2010,12,61\n";
        TuneCsv log = TuneCsv.parse(csv.replace("\n", "\r\n"));
        T.eq("cruise", log.profile, "profile");
        T.eq(4, log.count(), "row with blank rpm skipped");
        T.near(2000, log.rpm[2], 1e-9, "rpm col found case-insensitively");
        T.near(60, log.map[2], 1e-9, "map col");
        T.near(400, log.t[3], 1e-9, "time");
        boolean threw = false;
        try { TuneCsv.parse("rpm,x\n1,2\n"); } catch (IllegalArgumentException e) { threw = e.getMessage().contains("MAP"); }
        T.ok(threw, "no MAP column reported");
        CalDef d = realDef();
        CalTable t = new CalImage(image(d), d).table(d.table("sparkA"));
        int[][] h = log.hits(t);
        int total = 0;
        for (int[] r : h) for (int v : r) total += v;
        T.eq(4, total, "all samples counted");
        T.eq(2, h[CalTable.nearest(t.rowAxis, 800)][CalTable.nearest(t.colAxis, 30)], "two samples in the 800 rpm / 30 kPa cell");
        T.eq(2, h[CalTable.nearest(t.rowAxis, 2000)][CalTable.nearest(t.colAxis, 60)], "two in the 2000 rpm cell");
    }

    public static void positionsAndInterpolation() {
        T.near(0, CalTable.frac(new double[]{10, 20, 40}, 5), 1e-9, "clamp low");
        T.near(1.5, CalTable.frac(new double[]{10, 20, 40}, 30), 1e-9, "midway");
        T.near(2, CalTable.frac(new double[]{10, 20, 40}, 99), 1e-9, "clamp high");
        CalDef d = realDef();
        CalTable t = new CalImage(image(d), d).table(d.table("sparkA"));
        T.near((t.val[0][0] + t.val[0][1] + t.val[1][0] + t.val[1][1]) / 4, t.interp(0.5, 0.5), 1e-9, "bilinear");
    }

    public static void replayPlaysScrubsAndTrails() {
        StringBuilder sb = new StringBuilder("time_ms,RPM (rpm),MAP (kPa)\n");
        for (int i = 0; i <= 100; i++) sb.append(i * 100).append(',').append(800 + i * 20).append(',').append(30 + i / 2).append('\n');
        TuneCsv log = TuneCsv.parse(sb.toString());
        CalDef d = realDef();
        CalTable t = new CalImage(image(d), d).table(d.table("sparkA"));
        Replay r = new Replay(log, t);
        T.near(10000, r.length(), 1e-9, "length");
        T.ok(!r.advance(100), "paused does not move");
        r.play();
        r.advance(1000);
        T.near(1000, r.position(), 1e-9, "1x");
        T.eq(10, r.index(), "index");
        r.setSpeed(16);
        r.advance(100);
        T.near(2600, r.position(), 1e-9, "16x");
        r.seekFraction(0.5);
        T.near(5000, r.position(), 1e-9, "scrub");
        T.near(800 + 50 * 20, r.rpmNow(), 1e-9, "rpm at 5 s");
        double[][] tr = r.trail();
        T.ok(tr[0].length > 1 && tr[0].length <= Replay.TRAIL_POINTS + 1, "trail size " + tr[0].length);
        T.ok(tr[2][0] < tr[2][tr[2].length - 1], "oldest is faintest");
        T.near(1.0, tr[2][tr[2].length - 1], 1e-9, "newest is solid");
        r.seek(1e9);
        r.advance(1);
        T.ok(!r.playing(), "stops at the end");
        r.play();
        T.near(0, r.position(), 1e-9, "play at end restarts");
    }

    public static void chipGroups() {
        List<TableGroups.Group> g = TableGroups.build(realDef());
        T.eq(10, g.size(), "ten chips");
        T.eq("Idle spark curves", g.get(7).label, "label");
        T.eq(4, g.get(7).members.size(), "four idle curves");
        T.eq(2, g.get(9).members.size(), "shift up/down");
        int n = 0;
        for (TableGroups.Group x : g) n += x.members.size();
        T.eq(15, n, "every table reachable");
    }

    public static void cellInfoText() {
        CalDef d = realDef();
        byte[] a = image(d), b = image(d);
        CalImage ref = new CalImage(a, d);
        CalTable r = ref.table(d.table("sparkA"));
        b[r.addr[2][4]] += 1;
        CalTable cur = new CalImage(b, d).table(d.table("sparkA"));
        String s = CellInfo.describe(cur, r, 2, 4);
        T.ok(s.contains("Main spark A") && s.contains("raw 0x17 (23)") && s.contains("11.5 deg") && s.contains("compare 11.0 (+0.5)"), s);
        T.ok(s.contains(String.format("@%06X", r.addr[2][4])), "address: " + s);
        T.ok(CellInfo.describe(cur, r, 0, 0).contains("same as compare file"), "unchanged cell");
        T.eq("", CellInfo.describe(cur, null, 99, 0), "out of range");
    }

    public static void partNumberIsBcdNotAscii() {
        CalDef d = realDef();
        CalImage ok = new CalImage(image(d), d);
        T.eq("56041519", ok.bcd(0x273F, 4), "BCD digits at 0x273F");
        T.eq("56041519", ok.bcd(0x2746, 4), "BCD digits at 0x2746");
        T.eq("Part 56041519 \u2713", ok.partCheck().text, "ok text");
        byte[] other = image(d);
        setPart(other, 0x56, 0x04, 0x15, 0x20);
        CalImage.PartCheck bad = new CalImage(other, d).partCheck();
        T.ok(!bad.partFound && !bad.good(), "different part flagged");
        T.ok(bad.text.contains("mismatch") && bad.text.contains("56041520") && bad.text.contains("56041519"), bad.text);
        byte[] junk = image(d);
        junk[0x273F] = (byte) 0xAB;
        T.eq(null, new CalImage(junk, d).bcd(0x273F, 4), "non-BCD nibble");
        // ASCII text alone no longer counts
        byte[] ascii = new byte[d.imageSize];
        byte[] txt = "56041519AE".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(txt, 0, ascii, 0x100, txt.length);
        T.ok(!new CalImage(ascii, d).partCheck().partFound, "ASCII is not the part number");
        T.ok(new CalImage(new byte[100], d).bcd(0x273F, 4) == null, "short file");
    }

    public static void numberFormatting() {
        CalDef d = realDef();
        CalImage im = new CalImage(image(d), d);
        CalTable a = im.table(d.table("sparkA"));
        T.eq("5300", CellInfo.num(a.rowAxis[16], a.rowDec), "rpm whole");
        T.eq(0, a.colDec, "kPa whole");
        T.eq(1, a.dec, "degrees 1 decimal");
        T.eq("12.5", Units.format(12.46, "deg", 3), "deg");
        T.eq("0.53", Units.format(0.5304, "ms", 3), "ms 2 decimals");
        T.eq("60", Units.format(59.8, "kPa", 1), "kPa");
        T.eq("5300", Units.format(5300.2, "rpm", 1), "rpm");
        T.eq("42", Units.format(41.7, "TPS counts above closed", 2), "counts");
        T.eq("0.60", Units.format(0.6, "unknown", 2), "other units keep their step");
        CalTable f = im.table(d.table("fuelA"));
        T.ok(CellInfo.describe(f, null, 1, 3).contains("ms"), "fuel in ms");
        T.eq(2, f.dec, "ms 2");
    }
}
