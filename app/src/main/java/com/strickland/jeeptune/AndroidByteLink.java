package com.strickland.jeeptune;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import com.strickland.jeeptune.core.ByteLink;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

/** The dongle's Bluetooth serial socket. Connect order is the one the Link Test proved on the tablet. */
final class AndroidByteLink implements ByteLink {

    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    /** 0 = standard serial (secure), 1 = standard serial (insecure), 2 = serial channel 4. */
    static final int MODES = 3;

    private final BluetoothSocket socket;
    private final InputStream in;
    private final OutputStream out;
    final int mode;

    private AndroidByteLink(BluetoothSocket socket, int mode) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.mode = mode;
    }

    /** Tries one connect mode; throws IOException when the dongle can't be reached that way. */
    @SuppressLint("MissingPermission")
    static AndroidByteLink connect(BluetoothDevice dev, int mode) throws IOException {
        BluetoothSocket s = null;
        try {
            if (mode == 0) {
                s = dev.createRfcommSocketToServiceRecord(SPP);
            } else if (mode == 1) {
                s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
            } else {
                Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
                s = (BluetoothSocket) m.invoke(dev, 4);
            }
            s.connect();
            return new AndroidByteLink(s, mode);
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        } catch (SecurityException e) {
            closeQuietly(s);
            throw e;
        } catch (Exception e) {
            closeQuietly(s);
            throw new IOException(e.toString());
        }
    }

    InputStream input() { return in; }

    @Override
    public void write(byte[] b) throws IOException {
        out.write(b);
        out.flush();
    }

    @Override
    public void close() { closeQuietly(socket); }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) { }
    }
}
