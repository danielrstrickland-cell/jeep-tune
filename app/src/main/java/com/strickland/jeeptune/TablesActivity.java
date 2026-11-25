package com.strickland.jeeptune;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.strickland.jeeptune.core.CalDef;
import com.strickland.jeeptune.core.CalImage;
import com.strickland.jeeptune.core.CalTable;
import com.strickland.jeeptune.core.CellInfo;
import com.strickland.jeeptune.core.ImageDiff;
import com.strickland.jeeptune.core.ReadJob;
import com.strickland.jeeptune.core.Replay;
import com.strickland.jeeptune.core.TableGroups;
import com.strickland.jeeptune.core.TuneCsv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Calibration table viewer. READ-ONLY: it decodes tables from a .bin file the owner already has and draws them. It
 * never talks to the engine computer, never writes or edits a .bin, and sends no dongle frames.
 */
public final class TablesActivity extends Activity implements TableRenderer.Listener {
    private static final int REQ_OPEN = 1, REQ_COMPARE = 2, REQ_LOG = 3;
    private static final String ACTION_LIVE = "com.strickland.jeepdash.action.LIVE";
    private static final long LIVE_STALE_MS = 2500;
    private static final int BG = MainActivity.BG, PANEL = MainActivity.PANEL, BORDER = MainActivity.BORDER, TEXT = MainActivity.TEXT,
            DIM = MainActivity.DIM, AMBER = MainActivity.AMBER, AMBER_BG = MainActivity.AMBER_BG, AMBER_TX = MainActivity.AMBER_TX,
            GREEN = MainActivity.GREEN, RED = MainActivity.RED, CHIP = MainActivity.CHIP, MUTED = MainActivity.MUTED;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private CalDef def;
    private List<TableGroups.Group> groups;
    private int gi, mi;

    private CalImage cur, ref;
    private String curName = "", refName = "";
    private CalTable curT, refT;
    private ImageDiff diff;
    private TuneCsv csv;
    private String csvName = "";
    private int[][] hits;
    private Replay replay;
    private int mode = TableRenderer.GRID;
    private boolean showHits, showGhost;
    private double liveRpm = Double.NaN, liveMap = Double.NaN;
    private long liveAt;

    private TableRenderer renderer;
    private TextView tvFile, tvPart, tvSha, tvCompare, tvInfo, tvReplayTime, tvLogName, tvSliceLabel;
    private LinearLayout chipRow, subRow, replayRow, sliceBox;
    private Button bGrid, bSurface, bSlice, bChanges, bPlay, bS1, bS4, bS16;
    private CheckBox cbHits, cbGhost;
    private SeekBar sbSlice, sbReplay;
    private boolean seeking;
    private long lastTick;

    private final BroadcastReceiver liveReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            float rpm = i.getFloatExtra("rpm", Float.NaN), map = i.getFloatExtra("map_kpa", Float.NaN);
            if (Float.isNaN(rpm) || Float.isNaN(map)) return;
            liveRpm = rpm;
            liveMap = map;
            liveAt = SystemClock.elapsedRealtime();
            pushLive();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            long dt = now - lastTick;
            lastTick = now;
            if (replay != null && replay.advance(dt)) {
                pushReplay();
                if (!replay.playing()) bPlay.setText("Play");
            }
            if (!Double.isNaN(liveRpm) && now - liveAt > LIVE_STALE_MS) {
                liveRpm = Double.NaN;
                liveMap = Double.NaN;
                pushLive();
            }
            ui.postDelayed(this, 70);
        }
    };

    // ---------- lifecycle ----------

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        try (InputStream in = getAssets().open("defs/56041519.json")) {
            def = CalDef.parse(new String(readAll(in, 1 << 20), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, "Cannot read the table definitions: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        groups = TableGroups.build(def);
        renderer = new PlotlyRenderer(this);
        renderer.setListener(this);
        setContentView(build());
        renderChips();
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(ACTION_LIVE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(liveReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(liveReceiver, f);
        lastTick = SystemClock.elapsedRealtime();
        ui.post(tick);
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(tick);
        try { unregisterReceiver(liveReceiver); } catch (IllegalArgumentException ignored) { }
        if (replay != null) { replay.pause(); bPlay.setText("Play"); }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (renderer != null) renderer.destroy();
        super.onDestroy();
    }

    // ---------- screen ----------

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private android.graphics.drawable.GradientDrawable shape(int fill, int stroke, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(1), stroke);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setMinWidth(dp(56));
        b.setMinimumWidth(dp(56));
        b.setPadding(dp(10), 0, dp(10), 0);
        style(b, false);
        return b;
    }

    private void style(Button b, boolean selected) {
        b.setBackground(shape(selected ? AMBER_BG : CHIP, selected ? AMBER : BORDER, 8));
        b.setTextColor(selected ? AMBER_TX : TEXT);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams gap(int w, int h, int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = dp(left);
        return p;
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(6), dp(14), dp(6));

        LinearLayout head = row();
        Button back = button("‹ Back");
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView title = text("Tables", 20, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, gap(-2, -2, 12));
        TextView ro = text("READ-ONLY", 12, 0xFF1A1206);
        ro.setTypeface(Typeface.DEFAULT_BOLD);
        ro.setBackground(shape(AMBER, AMBER, 20));
        ro.setPadding(dp(10), dp(4), dp(10), dp(4));
        head.addView(ro, gap(-2, -2, 12));
        View sp = new View(this);
        head.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        Button open = button("Open .bin"), cmp = button("Compare…"), log = button("Tune log…"), sc = button("Scalars");
        bChanges = button("Changes");
        open.setOnClickListener(v -> chooseImage(false));
        cmp.setOnClickListener(v -> chooseImage(true));
        log.setOnClickListener(v -> pickDocument(REQ_LOG));
        sc.setOnClickListener(v -> showScalars());
        bChanges.setOnClickListener(v -> showChanges());
        head.addView(open, gap(-2, -2, 0));
        head.addView(cmp, gap(-2, -2, 8));
        head.addView(log, gap(-2, -2, 8));
        head.addView(sc, gap(-2, -2, 8));
        head.addView(bChanges, gap(-2, -2, 8));
        root.addView(head);

        LinearLayout info = row();
        tvFile = text("No file open", 13, TEXT);
        tvFile.setTypeface(Typeface.DEFAULT_BOLD);
        tvPart = text("", 12, DIM);
        info.addView(tvFile);
        info.addView(tvPart, gap(-2, -2, 14));
        tvCompare = text("", 12, AMBER_TX);
        info.addView(tvCompare, gap(-2, -2, 14));
        root.addView(info);
        tvSha = text("", 10, MUTED);
        tvSha.setTypeface(Typeface.MONOSPACE);
        tvSha.setOnClickListener(v -> copySha());
        root.addView(tvSha);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chipRow = row();
        hs.addView(chipRow);
        root.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        HorizontalScrollView hs2 = new HorizontalScrollView(this);
        hs2.setHorizontalScrollBarEnabled(false);
        subRow = row();
        hs2.addView(subRow);
        root.addView(hs2, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout ctl = row();
        bGrid = button("Grid");
        bSurface = button("3D");
        bSlice = button("Slice");
        bGrid.setOnClickListener(v -> setMode(TableRenderer.GRID));
        bSurface.setOnClickListener(v -> setMode(TableRenderer.SURFACE));
        bSlice.setOnClickListener(v -> setMode(TableRenderer.SLICE));
        ctl.addView(bGrid);
        ctl.addView(bSurface, gap(-2, -2, 6));
        ctl.addView(bSlice, gap(-2, -2, 6));
        cbHits = check("Hit map");
        cbGhost = check("Ghost stock");
        cbHits.setOnCheckedChangeListener((v, on) -> { showHits = on; renderer.setShowHits(on); });
        cbGhost.setOnCheckedChangeListener((v, on) -> { showGhost = on; renderer.setShowGhost(on); });
        ctl.addView(cbHits, gap(-2, -2, 16));
        ctl.addView(cbGhost, gap(-2, -2, 8));
        sliceBox = row();
        tvSliceLabel = text("", 12, AMBER_TX);
        tvSliceLabel.setMinWidth(dp(110));
        sbSlice = new SeekBar(this);
        sbSlice.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { sliceRow(p); }
        });
        sliceBox.addView(tvSliceLabel);
        sliceBox.addView(sbSlice, new LinearLayout.LayoutParams(0, -2, 1));
        ctl.addView(sliceBox, new LinearLayout.LayoutParams(0, -2, 1));
        ((LinearLayout.LayoutParams) sliceBox.getLayoutParams()).leftMargin = dp(16);
        root.addView(ctl);

        root.addView(renderer.view(), new LinearLayout.LayoutParams(-1, 0, 1));

        replayRow = row();
        tvLogName = text("", 12, DIM);
        bPlay = button("Play");
        bPlay.setOnClickListener(v -> togglePlay());
        sbReplay = new SeekBar(this);
        sbReplay.setMax(1000);
        sbReplay.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && replay != null) { replay.seekFraction(p / 1000.0); pushReplay(); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { seeking = false; }
        });
        tvReplayTime = text("0:00 / 0:00", 12, TEXT);
        bS1 = button("1x");
        bS4 = button("4x");
        bS16 = button("16x");
        bS1.setOnClickListener(v -> speed(1));
        bS4.setOnClickListener(v -> speed(4));
        bS16.setOnClickListener(v -> speed(16));
        replayRow.addView(bPlay);
        replayRow.addView(sbReplay, new LinearLayout.LayoutParams(0, -2, 1));
        replayRow.addView(tvReplayTime, gap(-2, -2, 8));
        replayRow.addView(bS1, gap(-2, -2, 8));
        replayRow.addView(bS4, gap(-2, -2, 4));
        replayRow.addView(bS16, gap(-2, -2, 4));
        replayRow.addView(tvLogName, gap(-2, -2, 10));
        replayRow.setVisibility(View.GONE);
        root.addView(replayRow);

        tvInfo = text("Tap a cell for its address and value.", 12, DIM);
        tvInfo.setTypeface(Typeface.MONOSPACE);
        tvInfo.setMinHeight(dp(20));
        root.addView(tvInfo);
        return root;
    }

    private CheckBox check(String s) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextColor(TEXT);
        c.setTextSize(13);
        return c;
    }

    /** SeekBar listener with every method optional. */
    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }

    private void renderChips() {
        chipRow.removeAllViews();
        for (int i = 0; i < groups.size(); i++) {
            final int g = i;
            Button c = button(groups.get(i).label + (groups.get(i).members.get(0).uncertain() && groups.get(i).members.size() == 1 ? " ?" : ""));
            style(c, i == gi);
            c.setOnClickListener(v -> select(g, 0));
            chipRow.addView(c, gap(-2, -2, i == 0 ? 0 : 6));
        }
        subRow.removeAllViews();
        TableGroups.Group grp = groups.get(gi);
        subRow.setVisibility(grp.members.size() > 1 ? View.VISIBLE : View.GONE);
        for (int m = 0; m < grp.members.size(); m++) {
            final int mm = m;
            CalDef.TableDef t = grp.members.get(m);
            Button c = button(t.cleanName() + (t.uncertain() ? " ?" : ""));
            c.setTextSize(12);
            style(c, m == mi);
            c.setOnClickListener(v -> select(gi, mm));
            subRow.addView(c, gap(-2, -2, m == 0 ? 0 : 6));
        }
    }

    // ---------- choosing files ----------

    private void pickDocument(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, req);
    }

    private void chooseImage(boolean forCompare) {
        List<ReadJob> jobs = new ArrayList<>();
        ReadJob stock = null;
        for (ReadJob j : TuneApp.engine.store.all()) {      // oldest first
            if (!j.complete()) continue;
            jobs.add(j);
            if (stock == null && !j.verifiedOf.isEmpty() && j.size == ReadJob.FULL) stock = j;
        }
        java.util.Collections.reverse(jobs);               // newest first
        if (forCompare && stock != null) { jobs.remove(stock); jobs.add(0, stock); }
        final List<ReadJob> list = jobs;
        final ReadJob stockJob = stock;
        List<String> names = new ArrayList<>();
        for (ReadJob j : list) {
            names.add((j == stockJob && forCompare ? "Stock: " : "") + j.id + "  ·  " + (j.size / 1024) + " KB"
                    + (j.verifiedOf.isEmpty() ? "" : "  ·  verified"));
        }
        names.add("Pick a file…");
        final boolean canClear = forCompare && ref != null;
        if (canClear) names.add("Clear compare");
        new AlertDialog.Builder(this)
                .setTitle(forCompare ? "Compare with…" : "Open a .bin")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which < list.size()) {
                        ReadJob j = list.get(which);
                        setImage(j.data.clone(), j.id + ".bin", forCompare);
                    } else if (which == list.size()) {
                        pickDocument(forCompare ? REQ_COMPARE : REQ_OPEN);
                    } else {
                        ref = null;
                        refName = "";
                        diff = null;
                        refresh();
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("cannot open the file");
                final byte[] bytes = readAll(in, req == REQ_LOG ? 64 << 20 : 8 << 20);
                final String name = displayName(uri);
                ui.post(() -> {
                    if (req == REQ_LOG) setLog(bytes, name);
                    else setImage(bytes, name, req == REQ_COMPARE);
                });
            } catch (IOException e) {
                ui.post(() -> Toast.makeText(this, "Cannot read it: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "tables-open").start();
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new IOException("file is too large");
        }
        return out.toByteArray();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) { }
        String p = uri.getLastPathSegment();
        return p == null ? "file" : p;
    }

    private void setImage(byte[] bytes, String name, boolean asCompare) {
        CalImage im = new CalImage(bytes, def);
        if (asCompare) {
            ref = im;
            refName = name;
        } else {
            cur = im;
            curName = name;
        }
        diff = cur != null && ref != null ? ImageDiff.compare(cur, ref) : null;
        if (hits != null || csv != null) hits = null;
        refresh();
    }

    private void setLog(byte[] bytes, String name) {
        try {
            csv = TuneCsv.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (csv.count() == 0) {
            Toast.makeText(this, "No rpm/MAP samples in this log", Toast.LENGTH_LONG).show();
            csv = null;
            return;
        }
        csvName = name;
        refresh();
    }

    // ---------- showing things ----------

    private void select(int g, int m) {
        gi = g;
        mi = m;
        renderChips();
        refresh();
    }

    private void setMode(int m) {
        mode = m;
        renderer.setMode(m);
        render();
    }

    private void sliceRow(int r) {
        renderer.setSliceRow(r);
        if (curT != null && curT.ok() && !curT.curve && r < curT.rows) {
            tvSliceLabel.setText(String.format(java.util.Locale.US, "%.0f rpm (row %d)", curT.rowAxis[r], r));
            if (!curT.rowKnown) tvSliceLabel.setText("row " + r);
        }
    }

    /** Re-decodes the selected table for the open files and sends it to the renderer. */
    private void refresh() {
        CalDef.TableDef t = groups.get(gi).members.get(mi);
        curT = cur == null ? null : cur.table(t);
        refT = ref == null ? null : ref.table(t);
        hits = csv != null && curT != null && curT.hasRealAxes() ? csv.hits(curT) : null;
        if (replay == null && csv != null) replay = new Replay(csv, curT);
        if (replay != null) replay.setTable(curT != null && curT.hasRealAxes() ? curT : null);
        if (curT != null) {
            renderer.setTable(curT, refT, hits);
            if (curT.ok() && !curT.curve) {
                sbSlice.setMax(Math.max(0, curT.rows - 1));
                sliceRow(Math.min(sbSlice.getProgress(), curT.rows - 1));
            }
        }
        pushReplay();
        pushLive();
        render();
    }

    private void render() {
        style(bGrid, mode == TableRenderer.GRID);
        style(bSurface, mode == TableRenderer.SURFACE);
        style(bSlice, mode == TableRenderer.SLICE);
        boolean grid = curT != null && curT.ok() && !curT.curve;
        bGrid.setEnabled(grid);
        bSurface.setEnabled(grid);
        bSlice.setEnabled(grid);
        sliceBox.setVisibility(grid && mode == TableRenderer.SLICE ? View.VISIBLE : View.INVISIBLE);
        cbHits.setEnabled(hits != null);
        cbGhost.setEnabled(ref != null);
        replayRow.setVisibility(csv != null ? View.VISIBLE : View.GONE);
        if (csv != null) {
            boolean ok = replay != null && curT != null && curT.hasRealAxes();
            bPlay.setEnabled(ok);
            tvLogName.setText(csvName + (csv.profile.isEmpty() ? "" : " · " + csv.profile) + (ok ? "" : " · axes unknown here"));
            style(bS1, replay != null && replay.speed() == 1);
            style(bS4, replay != null && replay.speed() == 4);
            style(bS16, replay != null && replay.speed() == 16);
        }
        if (cur == null) {
            tvFile.setText("No file open");
            tvPart.setText("Open a saved read or pick a .bin");
            tvSha.setText("");
        } else {
            CalImage.PartCheck pc = cur.partCheck();
            tvFile.setText(curName + "  ·  " + (cur.data.length / 1024) + " KB");
            tvPart.setText(pc.text);
            tvPart.setTextColor(pc.good() ? GREEN : RED);
            tvSha.setText("SHA-256 " + cur.sha256() + "  (tap to copy)");
        }
        tvCompare.setText(ref == null ? "" : "Compare: " + refName + (diff != null ? "  ·  " + diff.changedBytes + " bytes differ" : ""));
        bChanges.setVisibility(diff != null ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onCell(int r, int c) {
        if (curT != null) {
            String s = CellInfo.describe(curT, refT, r, c);
            if (hits != null && !curT.curve && showHits) s += "  ·  " + hits[r][c] + " samples";
            tvInfo.setText(s);
        }
    }

    @Override
    public void onSwipe(int dir) {
        int total = 0, at = 0;
        for (int g = 0; g < groups.size(); g++) {
            for (int m = 0; m < groups.get(g).members.size(); m++) {
                if (g == gi && m == mi) at = total;
                total++;
            }
        }
        int n = (at + dir + total) % total, g = 0;
        while (n >= groups.get(g).members.size()) { n -= groups.get(g).members.size(); g++; }
        select(g, n);
    }

    // ---------- replay and live ----------

    private void togglePlay() {
        if (replay == null) return;
        if (replay.playing()) { replay.pause(); bPlay.setText("Play"); }
        else { replay.play(); bPlay.setText(replay.playing() ? "Pause" : "Play"); lastTick = SystemClock.elapsedRealtime(); }
    }

    private void speed(int x) {
        if (replay != null) replay.setSpeed(x);
        render();
    }

    private void pushReplay() {
        if (replay == null || curT == null || !curT.hasRealAxes()) {
            renderer.setDot(TableRenderer.REPLAY, Double.NaN, Double.NaN);
            renderer.setTrail(new double[0], new double[0], new double[0]);
            return;
        }
        renderer.setDot(TableRenderer.REPLAY, replay.rowNow(), replay.colNow());
        double[][] tr = replay.trail();
        renderer.setTrail(tr[0], tr[1], tr[2]);
        if (!seeking) sbReplay.setProgress((int) Math.round(replay.fraction() * 1000));
        tvReplayTime.setText(Replay.clock(replay.position()) + " / " + Replay.clock(replay.length()));
    }

    private void pushLive() {
        if (curT == null || !curT.hasRealAxes() || Double.isNaN(liveRpm)) {
            renderer.setDot(TableRenderer.LIVE, Double.NaN, Double.NaN);
            return;
        }
        renderer.setDot(TableRenderer.LIVE, curT.rowPos(liveRpm), curT.colPos(liveMap));
    }

    // ---------- dialogs ----------

    private void showScalars() {
        if (cur == null) { Toast.makeText(this, "Open a .bin first", Toast.LENGTH_SHORT).show(); return; }
        StringBuilder sb = new StringBuilder();
        List<CalImage.Scalar> now = cur.scalars();
        List<CalImage.Scalar> was = ref == null ? null : ref.scalars();
        for (int i = 0; i < now.size(); i++) {
            sb.append(now.get(i).line());
            if (was != null && was.get(i).raw != now.get(i).raw) sb.append("\n    compare file: ").append(was.get(i).valueText());
            sb.append("\n\n");
        }
        sb.append("? = not confirmed yet");
        showText("Scalars", sb.toString());
    }

    private void showChanges() {
        if (diff == null) return;
        showText("Changes vs " + refName, android.text.TextUtils.join("\n", diff.lines(400)));
    }

    private void showText(String title, String body) {
        TextView t = text(body, 12, TEXT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(dp(18), dp(8), dp(18), dp(8));
        ScrollView s = new ScrollView(this);
        s.addView(t);
        new AlertDialog.Builder(this).setTitle(title).setView(s).setPositiveButton("Close", null).show();
    }

    private void copySha() {
        if (cur == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("SHA-256", cur.sha256()));
        Toast.makeText(this, "SHA-256 copied", Toast.LENGTH_SHORT).show();
    }
}
