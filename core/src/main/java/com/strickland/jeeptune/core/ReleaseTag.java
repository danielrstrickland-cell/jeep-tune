package com.strickland.jeeptune.core;

/** GitHub release tags look like "tune-12", where 12 is the build number and the app's version code. */
public final class ReleaseTag {
    private ReleaseTag() { }

    public static int build(String tag) {
        if (tag == null || !tag.startsWith("tune-")) return -1;
        try {
            return Integer.parseInt(tag.substring(5));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean isNewer(String latestTag, int myVersionCode) {
        int b = build(latestTag);
        return b > 0 && b > myVersionCode;
    }
}
