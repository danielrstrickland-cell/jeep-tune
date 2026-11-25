package com.strickland.jeeptune;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.strickland.jeeptune.core.ReadJob;
import com.strickland.jeeptune.core.ReadStatusText;

/**
 * Foreground service that keeps the process alive and visible while a flash read runs: ongoing notification with
 * progress, battery voltage and a Stop action, a partial wake lock, and the "dongle busy" broadcast for Jeep Dash.
 * The read itself runs on the engine's worker thread (app-wide singleton), so the activity can come and go.
 * The service stops itself when the read ends.
 */
public final class ReadService extends Service {
    static final String ACTION_STOP = "com.strickland.jeeptune.action.STOP_READ";
    /** Contract with Jeep Dash (see README): explicit broadcast, extra "busy" boolean. */
    public static final String ACTION_DONGLE_BUSY = "com.strickland.jeeptune.action.DONGLE_BUSY";
    public static final String EXTRA_BUSY = "busy";
    public static final String DASH_PACKAGE = "com.strickland.jeepdash";
    static final long HEARTBEAT_MS = 30_000;

    private static final String CHANNEL = "read";
    private static final int ID_ONGOING = 1, ID_DONE = 2;

    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private long lastBeat;
    private String lastText = "";
    private boolean sentBusy;

    /** Called by the activity right after TuneEngine.start() accepted a read. */
    static void launch(Context c) {
        Intent i = new Intent(c, ReadService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            TuneEngine eng = TuneApp.engine;
            if (!eng.active()) { finish(eng); return; }
            update(eng);
            long now = System.currentTimeMillis();
            if (now - lastBeat >= HEARTBEAT_MS) { broadcastBusy(true); lastBeat = now; }
            h.postDelayed(this, 1000);
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        TuneEngine eng = TuneApp.engine;
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            eng.pause();   // pause & save; the read can be resumed from the app
            return START_NOT_STICKY;
        }
        startForegroundNow(build(eng, "Starting read…"));
        if (wake == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jeeptune:read");
            wake.acquire(8 * 60 * 60 * 1000L);   // safety cap; released when the read ends
        }
        if (!sentBusy) { broadcastBusy(true); sentBusy = true; lastBeat = System.currentTimeMillis(); }
        h.removeCallbacks(tick);
        h.post(tick);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(tick);
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        broadcastBusy(false);   // always, so Jeep Dash is never left thinking the dongle is busy
        sentBusy = false;
        super.onDestroy();
    }

    private void finish(TuneEngine eng) {
        broadcastBusy(false);
        sentBusy = false;
        stopForeground(true);
        String msg = eng.message;
        if (msg != null && !msg.isEmpty()) {
            Notification n = base().setContentTitle("Jeep Tune").setContentText(msg)
                    .setStyle(new Notification.BigTextStyle().bigText(msg)).setAutoCancel(true).build();
            getSystemService(NotificationManager.class).notify(ID_DONE, n);
        }
        stopSelf();
    }

    private void update(TuneEngine eng) {
        ReadJob j = eng.job;
        if (j == null) return;
        String text = ReadStatusText.line(j.next, j.size, eng.liveVolts(), eng.lowBatteryPause());
        if (text.equals(lastText)) return;
        lastText = text;
        getSystemService(NotificationManager.class).notify(ID_ONGOING, build(eng, text));
    }

    private Notification.Builder base() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Flash read", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pi);
    }

    private Notification build(TuneEngine eng, String text) {
        Intent stop = new Intent(this, ReadService.class).setAction(ACTION_STOP);
        PendingIntent sp = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = base().setContentTitle("Jeep Tune").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Stop", sp).build());
        ReadJob j = eng.job;
        if (j != null && j.size > 0) b.setProgress(1000, (int) (j.next * 1000L / j.size), false);
        return b.build();
    }

    private void startForegroundNow(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(ID_ONGOING, n);
    }

    private void broadcastBusy(boolean busy) {
        Intent i = new Intent(ACTION_DONGLE_BUSY).setPackage(DASH_PACKAGE).putExtra(EXTRA_BUSY, busy);
        sendBroadcast(i);
    }
}
