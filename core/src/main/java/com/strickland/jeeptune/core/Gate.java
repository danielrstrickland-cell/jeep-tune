package com.strickland.jeeptune.core;

/** The start conditions. Returns null when all are met, otherwise what is missing. */
public final class Gate {
    private Gate() { }

    public static final double MIN_VOLTS = 12.0;

    public static String blocker(boolean connected, Ecu.Check c, boolean chargerTicked) {
        if (!connected) return "Dongle not connected";
        if (c == null || !c.answering) return "Engine computer not answering (key on?)";
        if (c.rpm != 0) return "Engine speed is not 0";
        if (c.volts < MIN_VOLTS) return "Battery below 12.0 V";
        if (!chargerTicked) return "Tick: Battery charger connected";
        return null;
    }
}
