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

    /** One byte of flash. Accepted only if the answer ends with the same three address bytes plus the value. */
    public Reply readByte(int addr) throws IOException {
        byte[] inner = Frames.read(addr);
        byte[] msg = Arrays.copyOfRange(inner, 4, 8);
        long t0 = System.nanoTime();
        DongleSession.Result r = send(inner, true);
        Reply p = new Reply();
        p.ms = (int) ((System.nanoTime() - t0) / 1000000L);
        p.tx = Hex.str(msg);
        byte[] d = r.data;
        p.rx = d == null ? "no answer" : Hex.str(d);
        if (d != null) {
            int off = d.length == 4 ? 0 : (d.length == 5 && (d[0] & 0xff) == 0x26 ? 1 : -1);
            if (off >= 0 && d[off] == msg[1] && d[off + 1] == msg[2] && d[off + 2] == msg[3]) {
                p.ok = true;
                p.value = d[off + 3] & 0xff;
            }
        }
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
