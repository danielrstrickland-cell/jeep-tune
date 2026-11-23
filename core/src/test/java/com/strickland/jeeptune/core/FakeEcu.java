package com.strickland.jeeptune.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A pretend dongle plus engine computer on the other end of a ByteLink. */
final class FakeEcu implements ByteLink {
    DongleSession session;
    final byte[] rom;
    final List<String> sent = new ArrayList<>();
    boolean echoPrefix26 = true;      // answer 26 HH MM LL VV (else HH MM LL VV)
    boolean wrongEcho = false;
    boolean answering = true;
    int rpm = 0;
    double volts = 12.6;
    int failNext = 0;                 // number of upcoming 26 requests that get no answer
    final Map<Integer, Integer> failAt = new HashMap<>();
    int rpmAfterReads = -1, rpmValue = 800;
    int reads = 0;
    int lastReadAddr = -1;

    FakeEcu(byte[] rom) { this.rom = rom; }

    static DongleSession attach(FakeEcu e) {
        e.session = new DongleSession(e);
        return e.session;
    }

    @Override
    public void write(byte[] f) {
        int seq = ((f[2] & 0xff) << 8) | (f[3] & 0xff);
        int il = ((f[9] & 0xff) << 8) | (f[10] & 0xff);
        byte[] inner = new byte[il];
        System.arraycopy(f, 11, inner, 0, il);
        sent.add(Hex.str(inner));
        reply(seq, new byte[]{0, 0, 2, 0, 0});
        if ((inner[0] & 0xff) != 0x61) return;
        byte[] data = null;
        if (answering) {
            if (inner[4] == 0x26) {
                int addr = ((inner[5] & 0xff) << 16) | ((inner[6] & 0xff) << 8) | (inner[7] & 0xff);
                reads++;
                if (rpmAfterReads >= 0 && reads > rpmAfterReads) rpm = rpmValue;
                Integer left = failAt.get(addr);
                if (failNext > 0) { failNext--; }
                else if (left != null && left > 0) { failAt.put(addr, left - 1); }
                else {
                    lastReadAddr = addr;
                    int v = rom[addr] & 0xff;
                    byte h = inner[5], m = inner[6], l = (byte) (wrongEcho ? inner[7] + 1 : inner[7]);
                    data = echoPrefix26 ? new byte[]{0x26, h, m, l, (byte) v} : new byte[]{h, m, l, (byte) v};
                }
            } else if (inner[4] == (byte) 0xf4 && inner[5] == 0x0a) {
                int raw = rpm * 8;
                data = new byte[]{(byte) (raw >> 8), 0x0b, (byte) raw};
            } else if (inner[4] == (byte) 0xf4 && inner[5] == 0x0f) {
                data = new byte[]{0x0f, (byte) Math.round(volts / 0.0625)};
            }
        }
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        if (data == null) { p.write(1); p.write(0xff); p.write(0xff); }
        else { p.write(1); p.write(0); p.write(data.length); p.write(data, 0, data.length); }
        reply(seq, p.toByteArray());
    }

    private void reply(int seq, byte[] payload) {
        int n = payload.length, nn = (~n) & 0xffff;
        byte[] f = new byte[8 + n + 1];
        f[0] = (byte) 0xaa; f[1] = 0x55;
        f[2] = (byte) (seq >> 8); f[3] = (byte) seq;
        f[4] = (byte) (n >> 8); f[5] = (byte) n;
        f[6] = (byte) (nn >> 8); f[7] = (byte) nn;
        System.arraycopy(payload, 0, f, 8, n);
        int x = 0;
        for (int i = 2; i < f.length - 1; i++) x ^= (f[i] & 0xff);
        f[f.length - 1] = (byte) ~x;
        session.onBytes(f, f.length);
    }

    @Override public void close() { }
}
