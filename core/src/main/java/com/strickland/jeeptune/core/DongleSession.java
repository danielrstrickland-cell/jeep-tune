package com.strickland.jeeptune.core;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Request/response over the dongle link. Every frame gets an ack (00 00 02 00 00) with the same seq;
 * queries also get a reply (01 | len(2) | data), where 01 ff ff means the vehicle did not answer.
 * The reader thread feeds bytes in with {@link #onBytes}.
 */
public final class DongleSession implements XtoolFrame.Listener {

    public static final class Result {
        public boolean acked, timedOut, noResponse, closed;
        public byte[] data;
    }

    private static final class Pending {
        final boolean wantReply;
        final CountDownLatch latch = new CountDownLatch(1);
        final Result result = new Result();
        Pending(boolean wantReply) { this.wantReply = wantReply; }
    }

    private final ByteLink link;
    private final XtoolFrame.Parser parser = new XtoolFrame.Parser(this);
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private int seq = 0;
    private volatile boolean closed;

    public DongleSession(ByteLink link) { this.link = link; }

    public Result send(byte[] inner, boolean wantReply, int timeoutMs) throws IOException {
        Pending p = new Pending(wantReply);
        if (closed) { p.result.closed = true; return p.result; }
        int mySeq;
        synchronized (this) { seq = (seq % 0xffff) + 1; mySeq = seq; }
        pending.put(mySeq, p);
        try {
            link.write(XtoolFrame.build(mySeq, inner));
        } catch (IOException e) {
            pending.remove(mySeq);
            throw e;
        }
        boolean done;
        try {
            done = p.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done = false;
        }
        if (!done) {
            pending.remove(mySeq);
            p.result.timedOut = true;
        }
        return p.result;
    }

    public void onBytes(byte[] b, int n) { parser.push(b, n); }

    @Override
    public void onFrame(int s, byte[] payload, boolean checksumOk) {
        if (!checksumOk) return;
        Pending p = pending.get(s);
        if (p == null) return;
        boolean isAck = payload.length == 5 && payload[0] == 0 && payload[1] == 0 && payload[2] == 2;
        if (isAck) {
            p.result.acked = true;
            if (!p.wantReply) complete(s, p);
            return;
        }
        // 01 = reply to a vehicle request; 02 = reply to the 5-baud wake-up (keywords)
        if (payload.length >= 3 && (payload[0] == 1 || payload[0] == 2)) {
            boolean none = (payload[1] & 0xff) == 0xff && (payload[2] & 0xff) == 0xff;
            p.result.acked = true;
            p.result.noResponse = none;
            if (!none) {
                int dl = Math.min(((payload[1] & 0xff) << 8) | (payload[2] & 0xff), payload.length - 3);
                byte[] d = new byte[dl];
                System.arraycopy(payload, 3, d, 0, dl);
                p.result.data = d;
            }
            complete(s, p);
        }
    }

    private void complete(int s, Pending p) {
        pending.remove(s);
        p.latch.countDown();
    }

    /** Fails every waiting request immediately and closes the link. */
    public void close() {
        closed = true;
        for (Map.Entry<Integer, Pending> e : pending.entrySet()) {
            e.getValue().result.closed = true;
            e.getValue().latch.countDown();
        }
        pending.clear();
        link.close();
    }
}
