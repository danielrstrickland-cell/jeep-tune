package com.strickland.jeeptune.core;

import java.util.List;

/** Maths behind the native viewer: camera, mesh, picking, tilt, motion gate, replay steps, tooltip. No Android needed. */
public class ViewTest {
    private static CalTable sparkA(byte[] img) {
        CalDef d = CalTest.realDef();
        return new CalImage(img, d).table(d.table("sparkA"));
    }

    public static void rampEndsAndMiddle() {
        T.eq(0xFFE6F1FB, Ramp.argb(0), "low is pale blue");
        T.eq(0xFFBA7517, Ramp.argb(1), "high is amber");
        T.eq(0xFFE6F1FB, Ramp.argb(-5), "clamped low");
        T.eq(0xFFBA7517, Ramp.argb(9), "clamped high");
        int mid = Ramp.argb(0.5);
        T.near((0xE6 + 0xBA) / 2.0, (mid >> 16) & 255, 1.0, "mid red");
    }

    public static void meshNormalsAndColours() {
        CalDef d = CalTest.realDef();
        byte[] img = CalTest.image(d);
        CalTable t = sparkA(img);
        SurfaceMesh m = SurfaceMesh.build(t, null, null, false, false);
        T.eq(17 * 9 * SurfaceMesh.FLOATS, m.verts.length, "vertex floats");
        T.eq(16 * 8 * 6, m.indices.length, "indices");
        // values rise with row and column, so the slope tilts normals; every normal is unit length with y > 0
        for (int i = 0; i < 17 * 9; i++) {
            int o = i * SurfaceMesh.FLOATS;
            double l = Math.sqrt(m.verts[o + 3] * m.verts[o + 3] + m.verts[o + 4] * m.verts[o + 4] + m.verts[o + 5] * m.verts[o + 5]);
            T.near(1, l, 1e-5, "unit normal");
            T.ok(m.verts[o + 4] > 0, "normal faces up");
        }
        T.ok(m.verts[3] < 0, "height rises with column, so the normal leans back toward -x");
        // lowest cell pale blue, highest amber
        T.near(0xE6 / 255.0, m.verts[6], 1e-3, "low colour red");
        int last = (16 * 9 + 8) * SurfaceMesh.FLOATS;
        T.near(0xBA / 255.0, m.verts[last + 6], 1e-3, "high colour red");
        T.near(SurfaceMesh.HEIGHT, m.verts[last + 1], 1e-5, "highest vertex at full height");
        T.ok(m.ghost == null, "no ghost without compare");
    }

    public static void flatSurfaceNormalIsUp() {
        double[][] h = new double[3][3];
        double[] x = {-1, 0, 1}, z = {1, 0, -1};
        double[] n = SurfaceMesh.normalOf(h, x, z, 1, 1);
        T.near(0, n[0], 1e-9, "nx");
        T.near(1, n[1], 1e-9, "ny");
        T.near(0, n[2], 1e-9, "nz");
    }

    public static void ghostTintAndOutlines() {
        CalDef d = CalTest.realDef();
        byte[] a = CalTest.image(d), b = CalTest.image(d);
        CalTable ref = sparkA(a);
        b[ref.addr[3][3]] += 6;     // raised
        b[ref.addr[5][5]] -= 6;     // lowered
        CalTable cur = sparkA(b);
        SurfaceMesh plain = SurfaceMesh.build(cur, ref, null, false, false);
        SurfaceMesh tint = SurfaceMesh.build(cur, ref, null, false, true);
        T.ok(tint.ghost != null && tint.ghost.length == tint.verts.length, "ghost made");
        T.near(0.3, tint.ghost[9], 1e-6, "ghost alpha 0.3");
        int o = (3 * 9 + 3) * SurfaceMesh.FLOATS, p = (5 * 9 + 5) * SurfaceMesh.FLOATS;
        T.ok(tint.verts[o + 6] > plain.verts[o + 6] * 0.9f || tint.verts[o + 8] < plain.verts[o + 8], "raised cell tinted warm");
        T.ok(tint.verts[p + 8] > plain.verts[p + 8] * 0.99f, "lowered cell tinted cool");
        T.ok(tint.verts[o + 8] < tint.verts[p + 8], "warm has less blue than cool");
        int plainSegs = plain.lines.length / SurfaceMesh.LINE_FLOATS / 2;
        T.eq(17 * 8 + 9 * 16 + 4 + 2 * 4, plainSegs, "grid + base + two change rings");
    }

    public static void hitColouring() {
        CalDef d = CalTest.realDef();
        CalTable t = sparkA(CalTest.image(d));
        int[][] hits = new int[17][9];
        hits[4][4] = 100;
        SurfaceMesh m = SurfaceMesh.build(t, null, hits, true, false);
        int o = (4 * 9 + 4) * SurfaceMesh.FLOATS, q = SurfaceMesh.FLOATS;
        T.near(0xBA / 255.0, m.verts[o + 6], 1e-3, "busiest cell is amber");
        T.near(0xE6 / 255.0, m.verts[q + 6], 1e-3, "empty cell is pale");
    }

    public static void cameraRaysPickTheCellUnderTheCursor() {
        CalDef d = CalTest.realDef();
        CalTable t = sparkA(CalTest.image(d));
        SurfaceMesh m = SurfaceMesh.build(t, null, null, false, false);
        Cam cam = new Cam();
        double w = 1000, h = 700;
        double[] vp = new double[16];
        cam.viewProj(w / h, vp, null);
        int[] want = {6, 3};
        double[] wp = new double[3];
        m.worldPos(want[0], want[1], wp);
        double[] px = cam.project(vp, wp[0], wp[1], wp[2], w, h);
        T.ok(px != null && px[0] > 0 && px[0] < w && px[1] > 0 && px[1] < h, "cell is on screen");
        double[] o = new double[3], dir = new double[3];
        T.ok(cam.ray(px[0], px[1], w, h, o, dir), "ray");
        int[] hit = m.pick(o, dir);
        T.ok(hit != null && hit[0] == want[0] && hit[1] == want[1], "picked " + (hit == null ? "nothing" : hit[0] + "," + hit[1]));
        T.ok(m.pick(new double[]{0, 5, 0}, new double[]{1, 0, 0}) == null, "miss");
        // straight overhead
        cam.pitch = Math.PI / 2 - 0.03;
        cam.yaw = 0;
        double[] o2 = new double[3], d2 = new double[3];
        cam.ray(w / 2, h / 2, w, h, o2, d2);
        int[] mid = m.pick(o2, d2);
        T.ok(mid != null && mid[0] == 8 && mid[1] == 4, "centre of the table from above");
    }

    public static void cameraGesturesAndReset() {
        Cam c = new Cam();
        c.orbit(100, 0);
        T.ok(c.yaw < Cam.DEF_YAW, "drag right turns the view");
        c.orbit(0, 5000);
        T.ok(c.pitch <= Math.PI / 2, "pitch clamped");
        c.zoom(2);
        T.near(Cam.DEF_DIST / 2, c.dist, 1e-9, "pinch out zooms in");
        c.zoom(1000);
        T.ok(c.dist >= 1.4, "zoom limit");
        c.twist(0.5);
        c.pan(40, 40, 700);
        T.ok(c.panX != 0 && c.panY != 0 && c.roll == 0.5, "pan and twist");
        c.tiltYaw = 0.3;
        c.reset();
        T.eq(Cam.DEF_YAW, c.yaw, "yaw reset");
        T.eq(0.0, c.roll, "roll reset");
        T.eq(0.0, c.panX, "pan reset");
        T.near(0.3, c.tiltYaw, 1e-12, "tilt offset is separate from the reset");
        double[] a = new double[16], b = new double[16], inv = new double[16], id = new double[16];
        c.viewProj(1.5, a, null);
        T.ok(Mat4.invert(inv, a), "invertible");
        Mat4.mul(id, a, inv);
        for (int i = 0; i < 16; i++) T.near(i % 5 == 0 ? 1 : 0, id[i], 1e-6, "A * A^-1");
    }

    public static void tiltMaths() {
        float[] ident = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        double cs = Math.cos(0.3), sn = Math.sin(0.3);
        float[] aboutX = {1, 0, 0, 0, (float) cs, (float) -sn, 0, (float) sn, (float) cs};
        double[] r0 = TiltOrbit.relative(ident, aboutX, 0);
        T.near(0.3, r0[0], 1e-5, "rotation about x");
        T.near(0, r0[1], 1e-5, "none about y");
        double[] r1 = TiltOrbit.relative(ident, aboutX, 1);
        T.near(0, r1[0], 1e-5, "display rotated 90: x becomes y");
        T.near(-0.3, r1[1], 1e-5, "sign flips");
        double[] same = TiltOrbit.relative(aboutX, aboutX, 0);
        T.near(0, same[0], 1e-9, "no change from the reference");
        double[] g = TiltOrbit.gyroStep(1.0, 0, 0.1, 0);
        T.near(0.1, g[0], 1e-9, "gyro integrates");
        double[] cam = TiltOrbit.toCamera(new double[]{10, -10}, 1, 1);
        T.near(1, cam[0], 1e-9, "yaw limited");
        T.near(1, cam[1], 1e-9, "pitch limited");
    }

    public static void motionGate() {
        T.ok(!MotionGate.moving(100000, 0, Double.NaN), "no live data, no GPS: free to tilt");
        T.ok(MotionGate.moving(100000, 96000, Double.NaN), "LIVE 4 s ago blocks");
        T.ok(!MotionGate.moving(100000, 94000, Double.NaN), "LIVE 6 s ago is old");
        T.ok(MotionGate.moving(100000, 0, 5.1), "5.1 mph blocks");
        T.ok(!MotionGate.moving(100000, 0, 4.9), "4.9 mph is fine");
        T.near(11.18, MotionGate.mph(5), 0.01, "5 m/s is 11.2 mph");
    }

    public static void replayStepsAndToggle() {
        TuneCsv csv = TuneCsv.parse("time_ms,rpm,map\n0,1000,30\n1000,1500,40\n2000,2000,50\n3000,2500,60\n");
        Replay r = new Replay(csv, null);
        r.step(1);
        T.near(1000, r.position(), 1e-9, "forward 1 s");
        r.step(1);
        r.step(-1);
        T.near(1000, r.position(), 1e-9, "back 1 s");
        r.step(-5);
        T.near(0, r.position(), 1e-9, "clamped at start");
        r.step(10);
        T.near(3000, r.position(), 1e-9, "clamped at end");
        r.toggle();
        T.ok(r.playing(), "toggle plays (restarts from the end)");
        T.near(0, r.position(), 1e-9, "restarted");
        r.toggle();
        T.ok(!r.playing(), "toggle pauses");
    }

    public static void tooltipLines() {
        CalDef d = CalTest.realDef();
        byte[] a = CalTest.image(d), b = CalTest.image(d);
        CalTable ref = sparkA(a);
        b[ref.addr[2][4]] += 1;
        CalTable cur = sparkA(b);
        int[][] hits = new int[17][9];
        hits[2][4] = 42;
        List<String> l = CellInfo.tooltip(cur, ref, hits, 2, 4);
        String all = String.join("|", l);
        T.eq("Main spark A", l.get(0), "table name");
        T.ok(l.get(1).contains("rpm") && l.get(1).contains("kPa"), "axes: " + l.get(1));
        T.ok(all.contains("Value  11.5 deg"), all);
        T.ok(all.contains("Raw  0x17 (23)"), all);
        T.ok(all.contains(String.format("Address  0x%06X", ref.addr[2][4])), all);
        T.ok(all.contains("Log samples  42"), all);
        T.ok(all.contains("Stock  11.0") && all.contains("(+0.5)"), all);
        T.ok(!String.join("|", CellInfo.tooltip(cur, null, null, 2, 4)).contains("Log samples"), "no log, no hits line");
        T.ok(CellInfo.tooltip(cur, ref, hits, 99, 0).isEmpty(), "out of range");
    }
}
