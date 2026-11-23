package com.strickland.jeeptune.core;

/** Hex helpers: "60 cb" <-> bytes. */
public final class Hex {
    private Hex() { }

    public static byte[] bytes(String hex) {
        String s = hex.trim();
        if (s.isEmpty()) return new byte[0];
        String[] parts = s.split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = (byte) Integer.parseInt(parts[i], 16);
        return out;
    }

    public static String str(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(Character.forDigit((b[i] >> 4) & 0xf, 16)).append(Character.forDigit(b[i] & 0xf, 16));
        }
        return sb.toString();
    }
}
