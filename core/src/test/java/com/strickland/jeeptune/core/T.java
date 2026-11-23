package com.strickland.jeeptune.core;

import java.util.Arrays;
import java.util.Objects;

/** Tiny assert harness: no external libraries, so core tests run with plain javac/java. */
public final class T {
    private T() { }

    public static void eq(Object exp, Object act, String msg) {
        boolean same = (exp instanceof byte[] && act instanceof byte[])
                ? Arrays.equals((byte[]) exp, (byte[]) act)
                : Objects.equals(exp, act);
        if (!same) throw new AssertionError(msg + ": expected <" + exp + "> but was <" + act + ">");
    }

    public static void near(double exp, double act, double tol, String msg) {
        if (Double.isNaN(act) || Math.abs(exp - act) > tol)
            throw new AssertionError(msg + ": expected " + exp + " ±" + tol + " but was " + act);
    }

    public static void ok(boolean c, String msg) {
        if (!c) throw new AssertionError(msg);
    }
}
