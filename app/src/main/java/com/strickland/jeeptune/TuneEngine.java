package com.strickland.jeeptune;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.strickland.jeeptune.core.DongleSession;
import com.strickland.jeeptune.core.Ecu;
import com.strickland.jeeptune.core.Gate;
import com.strickland.jeeptune.core.JobStore;
import com.strickland.jeeptune.core.ReadJob;
import com.strickland.jeeptune.core.ReadRunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/** The dongle link, the idle engine-speed/battery check and the read job, all on one background thread. */
final class TuneEngine {
    final JobStore store;
    private final Context app;

    volatile String linkStatus = "Starting";
    volatile boolean connected;
    volatile Ecu.Check check;
    volatile ReadJob job;
    volatile boolean running;
    volatile String message = "";
    volatile JobStore.Compared compared;
    volatile boolean charger;
    private volatile ReadRunner runner;
    private volatile ReadJob pending;
    private Thread worker;
    private int lastGoodMode = -1;

    TuneEngine(Context app, File root) {
        this.app = app;
        root.mkdirs();
        this.store = new JobStore(root);
    }

    boolean hasPermission() {
        return Build.VERSION.SDK_INT < 31 || app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    synchronized void begin() {
        if (worker != null || !hasPermission()) return;
        worker = new Thread(this::loop, "tune-worker");
        worker.setDaemon(true);
        worker.start();
    }

    /** Picks up the newest job from disk when nothing is open. */
    void loadLatest() {
        if (job == null && !running) job = store.latest();
    }

    /** Returns what is missing, or null when the read was started. */
    String start(ReadJob j) {
        if (running || pending != null) return "A read is already running";
        String why = Gate.blocker(connected, check, charger);
        if (why != null) return why;
        job = j;
        compared = null;
        message = "";
        pending = j;
        return null;
    }

    void pause() {
        ReadRunner r = runner;
        if (r != null) r.requestStop();
    }

    private void loop() {
        while (true) {
            DongleSession s = null;
            try {
                s = connect();
                if (s == null) { sleep(3000); continue; }
                Ecu ecu = new Ecu(s);
                ecu.setup(false);
                connected = true;
                linkStatus = "GWBT connected";
                while (true) {
                    ReadJob p = pending;
                    if (p != null) {
                        pending = null;
                        runJob(ecu, p);
                        continue;
                    }
                    check = ecu.check();
                    sleep(1500);
                }
            } catch (IOException | RuntimeException e) {
                message = "Dongle link lost: " + e.getMessage();
            } finally {
                connected = false;
                check = null;
                linkStatus = "searching";
                if (s != null) s.close();
            }
            sleep(2000);
        }
    }

    private void runJob(Ecu ecu, ReadJob j) {
        ReadRunner rr = new ReadRunner(ecu);
        runner = rr;
        running = true;
        try {
            ReadRunner.Stop st = rr.run(j);
            message = st.message;
            if (st == ReadRunner.Stop.COMPLETE && !j.compareTo.isEmpty()) {
                try {
                    JobStore.Compared c = store.compare(j);
                    compared = c;
                    message = c.error != null ? c.error : c.verified ? "Verified copy: both reads match." : "Reads differ.";
                } catch (IOException e) {
                    message = "Compare failed: " + e.getMessage();
                }
            }
            if (st == ReadRunner.Stop.ERROR) connectedLost();
        } finally {
            j.close();
            running = false;
            runner = null;
        }
    }

    private void connectedLost() { connected = false; }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @SuppressLint("MissingPermission")
    private DongleSession connect() {
        BluetoothManager bm = app.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { linkStatus = "turn Bluetooth on"; return null; }
        BluetoothDevice dev = null;
        Set<BluetoothDevice> bonded = ad.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                String n = d.getName();
                if (n != null && n.startsWith("GWBT-")) { dev = d; break; }
            }
        }
        if (dev == null) { linkStatus = "pair the GWBT dongle in Bluetooth settings"; return null; }
        linkStatus = "connecting";
        boolean all = lastGoodMode < 0;
        for (int i = 0; i < AndroidByteLink.MODES; i++) {
            int mode = all ? i : lastGoodMode;
            try {
                AndroidByteLink link = AndroidByteLink.connect(dev, mode);
                lastGoodMode = mode;
                return open(link);
            } catch (IOException ignored) {
                // try the next way
            }
            if (!all) { lastGoodMode = -1; break; }
        }
        linkStatus = "can't reach the dongle";
        return null;
    }

    private DongleSession open(AndroidByteLink link) {
        final DongleSession s = new DongleSession(link);
        final InputStream in = link.input();
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[1024];
            try {
                while (true) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n > 0) s.onBytes(buf, n);
                }
            } catch (IOException | RuntimeException ignored) {
                // socket closed or dongle gone
            }
            s.close();
        }, "dongle-reader");
        reader.setDaemon(true);
        reader.start();
        return s;
    }
}
