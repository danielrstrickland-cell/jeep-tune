package com.strickland.jeeptune.core;

/**
 * Orbit camera for the 3D surface. Manual yaw/pitch/roll/zoom/pan come from touch; tiltYaw/tiltPitch are an extra offset
 * from the tablet's motion (kept apart so switching tilt off returns to the pose). Angles in radians.
 */
public final class Cam {
    public static final double FOV_DEG = 40, NEAR = 0.1, FAR = 30;
    public static final double DEF_YAW = -0.62, DEF_PITCH = 0.55, DEF_DIST = 3.7;
    public static final double[] TARGET = {0, 0.3, 0};

    public double yaw = DEF_YAW, pitch = DEF_PITCH, roll, dist = DEF_DIST, panX, panY;
    public double tiltYaw, tiltPitch;

    public void reset() {
        yaw = DEF_YAW;
        pitch = DEF_PITCH;
        roll = 0;
        dist = DEF_DIST;
        panX = 0;
        panY = 0;
    }

    public void orbit(double dxPx, double dyPx) {
        yaw -= dxPx * 0.008;
        pitch = clampPitch(pitch + dyPx * 0.008);
    }

    public void twist(double dAngle) { roll += dAngle; }

    /** scale > 1 zooms in. */
    public void zoom(double scale) {
        if (scale <= 0 || Double.isNaN(scale)) return;
        dist = Math.max(1.4, Math.min(10, dist / scale));
    }

    public void pan(double dxPx, double dyPx, double viewHeightPx) {
        double per = dist * Math.tan(Math.toRadians(FOV_DEG) / 2) * 2 / Math.max(1, viewHeightPx);
        panX = Math.max(-2.5, Math.min(2.5, panX - dxPx * per));
        panY = Math.max(-2.5, Math.min(2.5, panY + dyPx * per));
    }

    private static double clampPitch(double p) { return Math.max(0.03, Math.min(Math.PI / 2 - 0.03, p)); }

    /** View-projection matrix for the viewport aspect; eye (optional, length 3) gets the camera position. */
    public void viewProj(double aspect, double[] vp, double[] eye) {
        double y = yaw + tiltYaw, p = clampPitch(pitch + tiltPitch);
        double[] dir = {Math.cos(p) * Math.sin(y), Math.sin(p), Math.cos(p) * Math.cos(y)};
        double[] right = {Math.cos(y), 0, -Math.sin(y)};
        double[] upv = Mat4.norm(Mat4.cross(dir, right));      // perpendicular to both, points up
        double[] tgt = {TARGET[0] + right[0] * panX + upv[0] * panY, TARGET[1] + right[1] * panX + upv[1] * panY,
                TARGET[2] + right[2] * panX + upv[2] * panY};
        double[] e = {tgt[0] + dir[0] * dist, tgt[1] + dir[1] * dist, tgt[2] + dir[2] * dist};
        double cr = Math.cos(roll), sr = Math.sin(roll);
        double[] up = {upv[0] * cr + right[0] * sr, upv[1] * cr + right[1] * sr, upv[2] * cr + right[2] * sr};
        double[] view = new double[16], proj = new double[16];
        Mat4.lookAt(view, e, tgt, up);
        Mat4.perspective(proj, FOV_DEG, aspect, NEAR, FAR);
        Mat4.mul(vp, proj, view);
        if (eye != null) { eye[0] = e[0]; eye[1] = e[1]; eye[2] = e[2]; }
    }

    /** Ray through a pixel: origin[3] and unit direction[3]. False when the matrix cannot be inverted. */
    public boolean ray(double px, double py, double w, double h, double[] origin, double[] dir) {
        double[] vp = new double[16], inv = new double[16];
        viewProj(w / Math.max(1, h), vp, null);
        if (!Mat4.invert(inv, vp)) return false;
        double nx = px / w * 2 - 1, ny = 1 - py / h * 2;
        double[] a = new double[4], b = new double[4];
        Mat4.transform(inv, nx, ny, -1, 1, a);
        Mat4.transform(inv, nx, ny, 1, 1, b);
        for (int i = 0; i < 3; i++) { a[i] /= a[3]; b[i] /= b[3]; }
        double[] d = Mat4.norm(new double[]{b[0] - a[0], b[1] - a[1], b[2] - a[2]});
        System.arraycopy(a, 0, origin, 0, 3);
        System.arraycopy(d, 0, dir, 0, 3);
        return true;
    }

    /** Projects a world point to pixels (x right, y down); null when behind the camera. */
    public double[] project(double[] vp, double x, double y, double z, double w, double h) {
        double[] o = new double[4];
        Mat4.transform(vp, x, y, z, 1, o);
        if (o[3] <= 1e-6) return null;
        return new double[]{(o[0] / o[3] * 0.5 + 0.5) * w, (0.5 - o[1] / o[3] * 0.5) * h};
    }
}
