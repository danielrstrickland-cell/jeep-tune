package com.strickland.jeeptune.core;

/**
 * Turns device motion into small camera offsets. Both inputs end up as rotation angles about the SCREEN's horizontal (x)
 * and vertical (y) axes, using the display rotation (0, 1=90, 2=180, 3=270 like Surface.ROTATION_*).
 */
public final class TiltOrbit {
    private TiltOrbit() { }

    /** Device-axis rotation (rx, ry) to screen axes. */
    public static double[] toScreen(double rx, double ry, int rotation) {
        switch (rotation & 3) {
            case 1: return new double[]{ry, -rx};
            case 2: return new double[]{-rx, -ry};
            case 3: return new double[]{-ry, rx};
            default: return new double[]{rx, ry};
        }
    }

    /**
     * Rotation of the device now relative to the reference pose, from two row-major 3x3 rotation matrices (what
     * SensorManager.getRotationMatrixFromVector gives). Returns {about screen x, about screen y} in radians.
     */
    public static double[] relative(float[] ref, float[] cur, int rotation) {
        double[] d = new double[9];       // d = ref^T * cur
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < 3; k++) s += ref[k * 3 + i] * cur[k * 3 + j];
                d[i * 3 + j] = s;
            }
        double vx = (d[7] - d[5]) / 2, vy = (d[2] - d[6]) / 2, vz = (d[3] - d[1]) / 2;
        double s = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (s < 1e-9) return new double[]{0, 0};
        double ang = Math.asin(Math.min(1, s));
        return toScreen(vx / s * ang, vy / s * ang, rotation);
    }

    /** Integrates a gyroscope sample (rad/s, device axes) over dt seconds; returns {about screen x, about screen y}. */
    public static double[] gyroStep(double wx, double wy, double dtSec, int rotation) {
        return toScreen(wx * dtSec, wy * dtSec, rotation);
    }

    /** Camera offsets {yaw, pitch} for accumulated screen rotations {rx, ry}, scaled and limited to +-maxRad. */
    public static double[] toCamera(double[] screen, double gain, double maxRad) {
        double yaw = Math.max(-maxRad, Math.min(maxRad, -screen[1] * gain));
        double pitch = Math.max(-maxRad, Math.min(maxRad, screen[0] * gain));
        return new double[]{yaw, pitch};
    }
}
