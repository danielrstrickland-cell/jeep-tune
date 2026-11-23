package com.strickland.jeeptune.core;

import java.io.IOException;
import java.util.Arrays;

/** Talks to the engine computer through the dongle. Every frame goes through {@link Frames#check}. */
public final class Ecu {
    public static final int READ_TIMEOUT_MS = 1500;

    public static final class Reply {
        public boolean ok;
        public int value;
        public String tx, rx;
        public byte[] data;
        public int ms;
    }

    public static final class Check {
        public boolean answering;
        public int rpm = -1;
        public double volts = -1;
    }

    private final DongleSession session;

    public Ecu(DongleSession session) { this.session = session; }

    private DongleSession.Result send(byte[] inner, boolean wantReply) throws IOException {
        Frames.check(inner);
        DongleSession.Result r = session.send(inner, wantReply, READ_TIMEOUT_MS);
        if (r.closed) throw new IOException("dongle link closed");
        return r;
    }

    /** Line set-up for the slow Chrysler SCI line, then the dongle timing. */
    public void setup(boolean fastTiming) throws IOException {
        send(Frames.fixed(Frames.SELECT_SCI), false);
        send(Frames.fixed(Frames.KEEPALIVE_OFF), false);
        send(Frames.fixed(Frames.PINS), false);
        send(Frames.fixed(Frames.BAUD_SLOW_SETUP), false);
        send(Frames.fixed(fastTiming ? Frames.TIMING_FAST : Frames.TIMING_NORMAL), false);
    }

    /** Answer shapes seen from the computer. A: 5 bytes, bytes 1..3 = address, value = byte 4. B: 4 bytes, bytes 0..2 = address, value = byte 3. C (real car): 5 bytes MM MM LL LL VV, value = byte 4. */
    public enum Shape { A, B, C }

    /** Whether this answer fits this shape for this address. */
    public static boolean fits(byte[] d, int addr, Shape sh) {
        if (d == null) return false;
        switch (sh) {
            case A: return d.length == 5 && addrAt(d, 1, addr);
            case B: return d.length == 4 && addrAt(d, 0, addr);
            default:
                return d.length == 5 && (d[0] & 0xff) == ((addr >> 8) & 0xff) && (d[1] & 0xff) == ((addr >> 8) & 0xff)
                        && (d[2] & 0xff) == (addr & 0xff) && (d[3] & 0xff) == (addr & 0xff);
        }
    }

    /** First shape this answer fits (C checked first), or null. */
    public static Shape shapeOf(byte[] d, int addr) {
        for (Shape sh : new Shape[]{Shape.C, Shape.A, Shape.B}) if (fits(d, addr, sh)) return sh;
        return null;
    }

    private static boolean addrAt(byte[] d, int off, int addr) {
        return (d[off] & 0xff) == ((addr >> 16) & 0xff) && (d[off + 1] & 0xff) == ((addr >> 8) & 0xff)
                && (d[off + 2] & 0xff) == (addr & 0xff);
    }

    /** One byte of flash. Accepted only if the answer has the confirmed shape and its address bytes match. */
    public Reply readByte(int addr, Shape shape) throws IOException {
        Reply p = request(addr);
        if (shape != null && fits(p.data, addr, shape)) {
            p.ok = true;
            p.value = p.data[p.data.length - 1] & 0xff;
        }
        return p;
    }

    /** Sends the normal 26 request and returns the raw answer (ok stays false). */
    public Reply request(int addr) throws IOException {
        byte[] inner = Frames.read(addr);
        byte[] msg = Arrays.copyOfRange(inner, 4, 8);
        long t0 = System.nanoTime();
        DongleSession.Result r = send(inner, true);
        Reply p = new Reply();
        p.ms = (int) ((System.nanoTime() - t0) / 1000000L);
        p.tx = Hex.str(msg);
        p.data = r.data;
        p.rx = r.data == null ? "no answer" : Hex.str(r.data);
        return p;
    }

    /** Engine speed and battery in fast mode, then back to slow speed. */
    public Check check() throws IOException {
        Check c = new Check();
        try {
            send(Frames.fixed(Frames.SPEED_FAST), false);
            DongleSession.Result r = send(Frames.fixed(Frames.RPM), true);
            DongleSession.Result b = send(Frames.fixed(Frames.BATTERY), true);
            boolean rpmOk = r.data != null && r.data.length >= 3 && (r.data[1] & 0xff) == 0x0b;
            boolean batOk = b.data != null && b.data.length >= 2 && (b.data[0] & 0xff) == 0x0f;
            if (rpmOk) c.rpm = (((r.data[0] & 0xff) << 8) | (r.data[2] & 0xff)) / 8;
            if (batOk) c.volts = (b.data[1] & 0xff) * 0.0625;
            c.answering = rpmOk && batOk;
        } finally {
            send(Frames.fixed(Frames.SPEED_SLOW), false);
        }
        return c;
    }
}
