package com.strickland.jeeptune.core;

/** The .sha256 text that goes with an exported verified copy. First line works with `sha256sum -c`. */
public final class ExportText {
    private ExportText() { }

    public static String sha256File(String hash, String fileName, String partNumber, String jobId, String verifiedAgainst) {
        return hash + "  " + fileName + "\n"
                + "Part number: " + (partNumber == null || partNumber.isEmpty() ? "unknown (not read by this app)" : partNumber) + "\n"
                + "Job: " + jobId + "\n"
                + "Verified: two full reads match (" + verifiedAgainst + ")\n";
    }
}
