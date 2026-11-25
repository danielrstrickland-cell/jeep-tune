package com.strickland.jeeptune;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Display;

import com.strickland.jeeptune.core.TiltOrbit;

/**
 * Reads the tablet's motion (game rotation vector, or the gyroscope when there is none) and reports camera yaw/pitch offsets
 * relative to the pose it had when started. Nothing is stored or sent anywhere.
 */
final class TiltController implements SensorEventListener {
    interface Out { void orbit(double yawRad, double pitchRad); }

    private static final double GAIN = 1.1, MAX = 0.9, SMOOTH = 0.25, GYRO_LEAK = 0.4;

    private final Activity activity;
    private final SensorManager sm;
    private final Sensor rot, gyro;
    private final Out out;
    private boolean running;
    private float[] ref;
    private final float[] cur = new float[9];
    private double ax, ay;            // gyro: accumulated screen angles
    private double sx, sy;            // smoothed result
    private long lastNs;

    TiltController(Activity a, Out out) {
        activity = a;
        this.out = out;
        sm = (SensorManager) a.getSystemService(Activity.SENSOR_SERVICE);
        rot = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        gyro = sm == null || rot != null ? null : sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    boolean available() { return rot != null || gyro != null; }
    boolean running() { return running; }
    String sensorName() { return rot != null ? "game rotation vector" : gyro != null ? "gyroscope" : "none"; }

    void start() {
        if (running || !available()) return;
        ref = null;
        ax = ay = sx = sy = 0;
        lastNs = 0;
        running = sm.registerListener(this, rot != null ? rot : gyro, SensorManager.SENSOR_DELAY_GAME);
    }

    void stop() {
        if (!running) return;
        sm.unregisterListener(this);
        running = false;
        out.orbit(0, 0);
    }

    @SuppressWarnings("deprecation")
    private int rotation() {
        Display d = android.os.Build.VERSION.SDK_INT >= 30 ? activity.getDisplay() : activity.getWindowManager().getDefaultDisplay();
        return d == null ? 0 : d.getRotation();     // Surface.ROTATION_0..3 are 0..3
    }

    @Override public void onSensorChanged(SensorEvent e) {
        double[] screen;
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (lastNs == 0) { lastNs = e.timestamp; return; }
            double dt = (e.timestamp - lastNs) * 1e-9;
            lastNs = e.timestamp;
            double[] d = TiltOrbit.gyroStep(e.values[0], e.values[1], dt, rotation());
            double leak = Math.max(0, 1 - GYRO_LEAK * dt);       // drifts back to the starting pose
            ax = ax * leak + d[0];
            ay = ay * leak + d[1];
            screen = new double[]{ax, ay};
        } else {
            SensorManager.getRotationMatrixFromVector(cur, e.values);
            if (ref == null) ref = cur.clone();
            screen = TiltOrbit.relative(ref, cur, rotation());
        }
        double[] cam = TiltOrbit.toCamera(screen, GAIN, MAX);
        sx += (cam[0] - sx) * SMOOTH;
        sy += (cam[1] - sy) * SMOOTH;
        out.orbit(sx, sy);
    }

    @Override public void onAccuracyChanged(Sensor s, int accuracy) { }
}
