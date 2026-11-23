package com.strickland.jeeptune.core;

/**
 * GearWrench GWSMARTBT (XTOOL) frames.
 * TX: 55 aa | seq(2) | len(2) | ~len(2) | payload | ck     RX: same with aa 55.
 * payload = 01 | innerLen(2) | inner;  ck = NOT(XOR of every byte after the 2-byte magic).
 */
public final class XtoolFrame {
    private XtoolFrame() { }

    public interface Listener { void onFrame(int seq, byte[] payload, boolean checksumOk); }

    public static byte[] build(int seq, byte[] inner) {
        int n = inner.length + 3;
        int nn = (~n) & 0xffff;
        byte[] f = new byte[2 + 6 + n + 1];
        f[0] = 0x55; f[1] = (byte) 0xaa;
        f[2] = (byte) (seq >> 8); f[3] = (byte) seq;
        f[4] = (byte) (n >> 8); f[5] = (byte) n;
        f[6] = (byte) (nn >> 8); f[7] = (byte) nn;
        f[8] = 0x01; f[9] = (byte) (inner.length >> 8); f[10] = (byte) inner.length;
        System.arraycopy(inner, 0, f, 11, inner.length);
        int x = 0;
        for (int i = 2; i < f.length - 1; i++) x ^= (f[i] & 0xff);
        f[f.length - 1] = (byte) ~x;
        return f;
    }

    /** Reassembles dongle replies (aa 55 ...) from arbitrary byte chunks. */
    public static final class Parser {
        private final Listener listener;
        private byte[] buf = new byte[4096];
        private int len = 0;

        public Parser(Listener listener) { this.listener = listener; }

        public synchronized void push(byte[] data, int n) {
            if (len + n > buf.length) {
                byte[] nb = new byte[Math.max(buf.length * 2, len + n)];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
            }
            System.arraycopy(data, 0, buf, len, n);
            len += n;
            while (true) {
                int k = -1;
                for (int i = 0; i + 1 < len; i++) {
                    if ((buf[i] & 0xff) == 0xaa && (buf[i + 1] & 0xff) == 0x55) { k = i; break; }
                }
                if (k < 0) {
                    if (len > 1) { buf[0] = buf[len - 1]; len = 1; }
                    return;
                }
                if (k > 0) drop(k);
                if (len < 8) return;
                int seq = ((buf[2] & 0xff) << 8) | (buf[3] & 0xff);
                int plen = ((buf[4] & 0xff) << 8) | (buf[5] & 0xff);
                int nlen = ((buf[6] & 0xff) << 8) | (buf[7] & 0xff);
                if ((plen ^ nlen) != 0xffff) { drop(2); continue; }
                if (len < 8 + plen + 1) return;
                int x = 0;
                for (int i = 2; i < 8 + plen; i++) x ^= (buf[i] & 0xff);
                boolean ok = ((~x) & 0xff) == (buf[8 + plen] & 0xff);
                byte[] payload = new byte[plen];
                System.arraycopy(buf, 8, payload, 0, plen);
                drop(8 + plen + 1);
                listener.onFrame(seq, payload, ok);
            }
        }

        private void drop(int n) {
            System.arraycopy(buf, n, buf, 0, len - n);
            len -= n;
        }
    }
}
