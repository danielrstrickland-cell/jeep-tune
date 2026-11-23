package com.strickland.jeeptune;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.strickland.jeeptune.core.Ecu;
import com.strickland.jeeptune.core.Fmt;
import com.strickland.jeeptune.core.Gate;
import com.strickland.jeeptune.core.JobStore;
import com.strickland.jeeptune.core.ReadJob;

import java.io.IOException;

/** The one screen: Read Test. */
public final class MainActivity extends Activity {
    static final int BG = 0xFF0E1217, PANEL = 0xFF161C23, BORDER = 0xFF26303B, TEXT = 0xFFE8EDF2, DIM = 0xFFA3AFBC,
            AMBER = 0xFFF0A63A, AMBER_BG = 0xFF2A2114, AMBER_TX = 0xFFF7C67A, GREEN = 0xFF52C98E, RED = 0xFFE5604F,
            CHIP = 0xFF1B232C, MUTED = 0xFF8592A0, CODE_BG = 0xFF0B0F13;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TuneEngine eng;
    private int amount = ReadJob.FULL;
    private boolean fast = false;

    private TextView chipDongle, tvCheck1, tvCheck2, tvCheck3, ic1, ic2, ic3;
    private CheckBox charger;
    private Button bAmount1, bAmount2, bTimeN, bTimeF, bMain, bSave, bCompare;
    private TextView tvTitle, tvDone, tvAvg, tvRetries, tvLeft, tvSessions, tvSaved, tvMsg, tvBytes, tvLog;
    private ProgressBar bar;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            ui.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        eng = TuneApp.engine;
        eng.loadLatest();
        setContentView(build());
        if (!eng.hasPermission()) requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 1);
        else eng.begin();
        UpdateChecker.check(this, version(), false);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (eng.hasPermission()) eng.begin();
    }

    @Override protected void onResume() { super.onResume(); ui.post(tick); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); }

    private long version() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    // ---------- building the screen ----------

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable shape(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(strokeDp), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView mono(int sp, int color) {
        TextView t = text("", sp, color, false);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private LinearLayout col(int fill, int padH, int padV) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        if (fill != 0) l.setBackground(shape(fill, BORDER, 1, 12));
        l.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
        return l;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        return p;
    }

    private Button button(String s, int minH, int sp) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(sp);
        b.setMinHeight(dp(minH));
        b.setMinimumHeight(dp(minH));
        return b;
    }

    private void style(Button b, boolean selected, boolean enabled) {
        b.setEnabled(enabled);
        if (selected) {
            b.setBackground(shape(AMBER_BG, AMBER, 2, 8));
            b.setTextColor(AMBER_TX);
        } else {
            b.setBackground(shape(CHIP, BORDER, 1, 8));
            b.setTextColor(enabled ? TEXT : MUTED);
        }
        b.setAlpha(enabled ? 1f : 0.7f);
    }

    private TextView chip(String s) {
        TextView t = text(s, 13, 0xFFC9D3DD, false);
        t.setBackground(shape(CHIP, BORDER, 1, 20));
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        return t;
    }

    private LinearLayout checkRow(TextView[] out, String sub) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(44));
        TextView icon = text("○", 20, MUTED, true);
        icon.setGravity(Gravity.CENTER);
        r.addView(icon, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("", 14, TEXT, false);
        t.addView(title);
        t.addView(text(sub, 12, DIM, false));
        r.addView(t);
        out[0] = icon;
        out[1] = title;
        return r;
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(0xFF12171D);
        head.setPadding(dp(24), 0, dp(24), 0);
        TextView h1 = text("Jeep Tune", 22, TEXT, true);
        TextView h2 = text("  Read Test", 16, DIM, false);
        head.addView(h1);
        head.addView(h2);
        View sp = new View(this);
        head.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        chipDongle = chip("Dongle: ...");
        head.addView(chipDongle);
        TextView c2 = chip("Chrysler SCI · 7812.5 baud");
        head.addView(c2, lp(-2, -2, 0));
        ((LinearLayout.LayoutParams) c2.getLayoutParams()).leftMargin = dp(10);
        TextView ro = text("READ-ONLY", 13, 0xFF1A1206, true);
        ro.setBackground(shape(AMBER, AMBER, 1, 20));
        ro.setPadding(dp(12), dp(6), dp(12), dp(6));
        head.addView(ro, lp(-2, -2, 0));
        ((LinearLayout.LayoutParams) ro.getLayoutParams()).leftMargin = dp(10);
        root.addView(head, new LinearLayout.LayoutParams(-1, dp(64)));

        LinearLayout body = new LinearLayout(this);
        body.setPadding(dp(24), dp(16), dp(24), dp(16));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        // ----- left column
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        ScrollView ls = new ScrollView(this);
        ls.addView(left);
        body.addView(ls, new LinearLayout.LayoutParams(dp(372), -1));

        LinearLayout before = col(PANEL, 18, 14);
        before.addView(text("Before you start", 15, TEXT, true));
        TextView[] o = new TextView[2];
        before.addView(checkRow(o, "Engine speed reads 0 rpm"));
        ic1 = o[0]; tvCheck1 = o[1];
        before.addView(checkRow(o, "Engine computer answering"));
        ic2 = o[0]; tvCheck2 = o[1];
        before.addView(checkRow(o, "Test stops below 12.0 V"));
        ic3 = o[0]; tvCheck3 = o[1];
        tvCheck1.setText("Engine off");
        tvCheck2.setText("Key on");
        charger = new CheckBox(this);
        charger.setText("Battery charger connected\nYou confirm this one");
        charger.setTextColor(TEXT);
        charger.setTextSize(14);
        charger.setMinHeight(dp(48));
        charger.setOnCheckedChangeListener((v, on) -> eng.charger = on);
        charger.setChecked(eng.charger);
        before.addView(charger);
        left.addView(before, lp(-1, -2, 0));

        LinearLayout set = col(PANEL, 18, 14);
        set.addView(text("Test settings", 15, TEXT, true));
        set.addView(text("Amount to read (from address 000000)", 12, DIM, false), lp(-2, -2, 10));
        LinearLayout r1 = new LinearLayout(this);
        bAmount1 = button("1 KB test", 44, 14);
        bAmount2 = button("Full 256 KB", 44, 14);
        bAmount1.setOnClickListener(v -> { amount = ReadJob.KB1; render(); });
        bAmount2.setOnClickListener(v -> { amount = ReadJob.FULL; render(); });
        r1.addView(bAmount1, new LinearLayout.LayoutParams(0, -2, 1));
        r1.addView(bAmount2, new LinearLayout.LayoutParams(0, -2, 1));
        set.addView(r1, lp(-1, -2, 6));
        set.addView(text("Dongle timing", 12, DIM, false), lp(-2, -2, 12));
        LinearLayout r2 = new LinearLayout(this);
        bTimeN = button("Normal (120 ms)", 44, 14);
        bTimeF = button("Fast (30 ms)", 44, 14);
        bTimeN.setOnClickListener(v -> { fast = false; render(); });
        bTimeF.setOnClickListener(v -> { fast = true; render(); });
        r2.addView(bTimeN, new LinearLayout.LayoutParams(0, -2, 1));
        r2.addView(bTimeF, new LinearLayout.LayoutParams(0, -2, 1));
        set.addView(r2, lp(-1, -2, 6));
        left.addView(set, lp(-1, -2, 14));

        bMain = button("Start", 52, 16);
        bMain.setTypeface(Typeface.DEFAULT_BOLD);
        bMain.setOnClickListener(v -> onMain());
        left.addView(bMain, lp(-1, -2, 16));
        TextView note = text("Sends only flash-read requests (26). Nothing is written. Safe to stop any time: key off or close the app, "
                + "and it picks up where it left off. Stops on its own if the engine starts, the battery drops below 12.0 V, "
                + "or 5 reads in a row get no answer.", 12, DIM, false);
        left.addView(note, lp(-1, -2, 10));

        // ----- right column
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        ScrollView rs = new ScrollView(this);
        rs.addView(right);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1);
        rlp.leftMargin = dp(24);
        body.addView(rs, rlp);

        LinearLayout prog = col(PANEL, 20, 14);
        LinearLayout top = new LinearLayout(this);
        tvTitle = text("", 15, TEXT, true);
        tvDone = text("", 14, 0xFFC9D3DD, false);
        top.addView(tvTitle, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(tvDone);
        prog.addView(top);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.getProgressDrawable().setColorFilter(AMBER, android.graphics.PorterDuff.Mode.SRC_IN);
        prog.addView(bar, lp(-1, dp(12), 10));
        LinearLayout stats = new LinearLayout(this);
        tvAvg = stat(stats, "Avg per read");
        tvRetries = stat(stats, "Retries");
        tvLeft = stat(stats, "Time left at this speed");
        tvLeft.setTextColor(AMBER_TX);
        tvSessions = stat(stats, "Sessions so far");
        prog.addView(stats, lp(-1, -2, 12));
        tvSaved = text("", 13, 0xFFC9D3DD, false);
        prog.addView(tvSaved, lp(-1, -2, 12));
        tvMsg = text("", 14, AMBER_TX, true);
        prog.addView(tvMsg, lp(-1, -2, 6));
        right.addView(prog, lp(-1, -2, 0));

        LinearLayout hex = col(CODE_BG, 20, 12);
        hex.addView(text("Flash bytes as they come in (-- = not read yet)", 12, DIM, false));
        tvBytes = mono(13, TEXT);
        hex.addView(tvBytes, lp(-1, -2, 6));
        right.addView(hex, lp(-1, -2, 14));

        LinearLayout log = col(PANEL, 20, 10);
        log.addView(text("Log (saved with the file)", 12, DIM, false));
        tvLog = mono(12, 0xFFC9D3DD);
        log.addView(tvLog, lp(-1, -2, 4));
        right.addView(log, lp(-1, -2, 14));

        LinearLayout acts = new LinearLayout(this);
        acts.setGravity(Gravity.CENTER_VERTICAL);
        bSave = button("Save .bin + log", 48, 15);
        bCompare = button("Read again & compare", 48, 15);
        bSave.setOnClickListener(v -> onSave());
        bCompare.setOnClickListener(v -> onCompare());
        acts.addView(bSave);
        acts.addView(bCompare, lp(-2, -2, 0));
        ((LinearLayout.LayoutParams) bCompare.getLayoutParams()).leftMargin = dp(12);
        TextView hint = text("Two matching full reads = a verified copy.", 13, DIM, false);
        acts.addView(hint, lp(-2, -2, 0));
        ((LinearLayout.LayoutParams) hint.getLayoutParams()).leftMargin = dp(12);
        right.addView(acts, lp(-1, -2, 14));
        return root;
    }

    private TextView stat(LinearLayout row, String label) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(text(label, 12, DIM, false));
        TextView v = text("--", 20, TEXT, true);
        c.addView(v);
        row.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
        return v;
    }

    // ---------- actions ----------

    private void onMain() {
        ReadJob j = eng.job;
        if (eng.running) { eng.pause(); return; }
        try {
            if (j == null || j.complete()) j = eng.store.create(amount, fast, null);
            String why = eng.start(j);
            if (why != null) Toast.makeText(this, why, Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Cannot create the job: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onCompare() {
        ReadJob j = eng.job;
        if (j == null || !j.complete() || eng.running) return;
        String why = Gate.blocker(eng.connected, eng.check, eng.charger);
        if (why != null) { Toast.makeText(this, why, Toast.LENGTH_LONG).show(); return; }
        try {
            ReadJob second = eng.store.create(j.size, j.fastTiming, j.compareTo.isEmpty() ? j.id : j.compareTo);
            eng.start(second);
        } catch (IOException e) {
            Toast.makeText(this, "Cannot create the job: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onSave() {
        final ReadJob j = eng.job;
        if (j == null) return;
        new Thread(() -> {
            String m;
            try { m = Saver.save(getApplicationContext(), j); } catch (IOException e) { m = "Save failed: " + e.getMessage(); }
            final String msg = m;
            ui.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        }, "saver").start();
    }

    // ---------- drawing the state ----------

    private void mark(TextView icon, int state) {
        icon.setText(state > 0 ? "✓" : state < 0 ? "✕" : "○");
        icon.setTextColor(state > 0 ? GREEN : state < 0 ? RED : MUTED);
    }

    private void render() {
        boolean running = eng.running;
        ReadJob j = eng.job;
        Ecu.Check c = eng.check;
        chipDongle.setText("Dongle: " + eng.linkStatus);
        boolean have = c != null && c.answering;
        mark(ic1, !have ? 0 : c.rpm == 0 ? 1 : -1);
        mark(ic2, have ? 1 : 0);
        mark(ic3, !have ? 0 : c.volts >= Gate.MIN_VOLTS ? 1 : -1);
        tvCheck3.setText(have ? String.format("Battery %.1f V", c.volts) : "Battery -- V");
        if (have && c.rpm > 0) tvCheck1.setText("Engine off (reads " + c.rpm + " rpm)"); else tvCheck1.setText("Engine off");

        boolean locked = running || (j != null && !j.complete());
        int amt = locked && j != null ? j.size : amount;
        boolean fst = locked && j != null ? j.fastTiming : fast;
        style(bAmount1, amt == ReadJob.KB1, !locked);
        style(bAmount2, amt == ReadJob.FULL, !locked);
        style(bTimeN, !fst, !locked);
        style(bTimeF, fst, !locked);

        boolean canStart = Gate.blocker(eng.connected, c, eng.charger) == null;
        bMain.setText(running ? "Pause & save" : (j != null && !j.complete() && j.next > 0) ? "Resume" : "Start");
        style(bMain, true, running || canStart);
        if (!running && !canStart) { bMain.setBackground(shape(CHIP, BORDER, 1, 10)); bMain.setTextColor(MUTED); }

        byte[] view = j != null ? j.data : new byte[16 * 9];
        int next = j != null ? j.next : 0;
        int size = j != null ? j.size : amount;
        if (j == null) {
            tvTitle.setText((amount == ReadJob.FULL ? "Full read 000000 – 03FFFF" : "1 KB test 000000 – 0003FF") + " · not started");
        } else {
            String kind = j.size == ReadJob.FULL ? "Full read 000000 – 03FFFF" : "1 KB test 000000 – 0003FF";
            tvTitle.setText(kind + (!j.compareTo.isEmpty() ? " · second read" : "") + (j.sessions > 1 ? " · resumed" : ""));
        }
        tvDone.setText(Fmt.count(next) + " of " + Fmt.count(size) + " bytes · " + Fmt.percent(next, size) + "%");
        bar.setProgress(size == 0 ? 0 : (int) (next * 1000L / size));
        long avg = j == null ? 0 : j.avgMs();
        tvAvg.setText(avg > 0 ? avg + " ms" : "--");
        tvRetries.setText(String.valueOf(j == null ? 0 : j.retries));
        tvLeft.setText(j == null || j.complete() ? "--" : Fmt.timeLeft(size - next, avg));
        tvSessions.setText(String.valueOf(j == null ? 0 : j.sessions));
        StringBuilder s = new StringBuilder("Saved on the tablet every 64 bytes");
        if (j != null && j.lastSaveMs > 0) s.append(" · last save ").append(Fmt.ago(System.currentTimeMillis() - j.lastSaveMs));
        if (j != null && !j.resumeCheck.isEmpty()) s.append(" · resume check ").append(j.resumeCheck);
        tvSaved.setText(s.toString());

        String msg = eng.message;
        JobStore.Compared cmp = eng.compared;
        if (j != null && !j.verifiedOf.isEmpty()) {
            msg = "Verified copy";
            tvMsg.setTextColor(GREEN);
        } else {
            tvMsg.setTextColor(AMBER_TX);
            if (cmp != null && cmp.error == null && !cmp.verified) {
                StringBuilder m = new StringBuilder("Reads differ: " + Fmt.count(cmp.mismatches) + " bytes. First at");
                for (int a : cmp.first) m.append(String.format(" %06X", a));
                msg = m.toString();
            }
        }
        tvMsg.setText(msg == null ? "" : msg);
        tvMsg.setVisibility(msg == null || msg.isEmpty() ? View.GONE : View.VISIBLE);

        tvBytes.setText(Fmt.rows(view, next, 9));
        String[] lines = j == null ? new String[0] : j.recentLog();
        tvLog.setText(lines.length == 0 ? "-" : android.text.TextUtils.join("\n", lines));

        boolean done = j != null && j.complete() && !running;
        style(bSave, false, done);
        style(bCompare, false, done && canStart);
        if (running) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
