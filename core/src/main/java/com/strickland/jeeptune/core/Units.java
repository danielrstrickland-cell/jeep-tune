package com.strickland.jeeptune.core;

import java.util.Locale;

/** How many decimals each unit shows: rpm and counts whole, kPa whole, degrees 1 decimal, ms 2 decimals. */
public final class Units {
    private Units() { }

    /** Decimals to show for a value in this unit; fallback is used for units without a rule (mph, raw scale steps...). */
    public static int decimals(String unit, int fallback) {
        if (unit == null) return fallback;
        String u = unit.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("rpm")) return 0;
        if (u.equals("kpa")) return 0;
        if (u.equals("ms")) return 2;
        if (u.equals("deg") || u.equals("degc") || u.startsWith("degrees")) return 1;
        if (u.contains("count") || u.equals("raw") || u.equals("index")) return 0;
        return fallback;
    }

    public static String format(double v, String unit, int fallback) {
        return String.format(Locale.US, "%." + decimals(unit, fallback) + "f", v);
    }
}
