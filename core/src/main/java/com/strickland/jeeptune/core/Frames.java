package com.strickland.jeeptune.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Every message this app may send to the dongle, and the allow-list that refuses anything else.
 * Nothing here writes, erases, resets or actuates: only line set-up, speed/timing switches, three
 * kinds of read (flash byte 26, engine speed, battery).
 */
public final class Frames {
    private Frames() { }

    public static final class Refused extends RuntimeException {
        public Refused(String m) { super(m); }
    }

    public static final String SELECT_SCI = "60 05 19 01";
    public static final String KEEPALIVE_OFF = "60 06 00";
    public static final String PINS = "60 01 49 c4 21";
    public static final String BAUD_SLOW_SETUP = "60 02 00 00 1e 84 00 08";
    public static final String TIMING_NORMAL = "60 03 00 78 07 d0 00 14 00 05";
    public static final String TIMING_FAST = "60 03 00 1e 07 d0 00 14 00 05";
    public static final String TIMING_FASTER = "60 03 00 0a 07 d0 00 14 00 05";
    public static final String SPEED_FAST = "60 02 00 00 f4 24 00 08 ff";
    public static final String SPEED_SLOW = "60 02 00 00 1e 84 00 08 ff";
    public static final String RPM = "61 04 00 03 f4 0a 0b 01";
    public static final String BATTERY = "61 04 00 02 f4 0f 01";

    private static final Set<String> FIXED = new HashSet<>(Arrays.asList(
            SELECT_SCI, KEEPALIVE_OFF, PINS, BAUD_SLOW_SETUP, TIMING_NORMAL, TIMING_FAST, TIMING_FASTER,
            SPEED_FAST, SPEED_SLOW, RPM, BATTERY));

    /** 61 04 00 04 26 HH MM LL 01: read one byte of flash at a 24-bit address. */
    public static byte[] read(int addr) {
        if (addr < 0 || addr > 0xffffff) throw new Refused("address out of range");
        return new byte[]{0x61, 0x04, 0x00, 0x04, 0x26, (byte) (addr >> 16), (byte) (addr >> 8), (byte) addr, 0x01};
    }

    public static byte[] fixed(String hex) {
        if (!FIXED.contains(hex)) throw new Refused("not on the list: " + hex);
        return Hex.bytes(hex);
    }

    public static boolean allowed(byte[] f) {
        if (f == null) return false;
        if (FIXED.contains(Hex.str(f))) return true;
        return f.length == 9 && (f[0] & 0xff) == 0x61 && f[1] == 0x04 && f[2] == 0x00 && f[3] == 0x04
                && (f[4] & 0xff) == 0x26 && f[8] == 0x01;
    }

    /** Throws {@link Refused} for anything not on the list. Called before every send. */
    public static void check(byte[] f) {
        if (!allowed(f)) throw new Refused("refused (read-only app): " + (f == null ? "null" : Hex.str(f)));
    }
}
