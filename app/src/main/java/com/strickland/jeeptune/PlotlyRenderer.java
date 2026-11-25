package com.strickland.jeeptune;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.strickland.jeeptune.core.CalTable;
import com.strickland.jeeptune.core.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@link TableRenderer} that draws with the bundled plotly.js in a WebView (assets/viewer/viewer.html). No network. */
public final class PlotlyRenderer implements TableRenderer {
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> pending = new ArrayList<>();
    private boolean ready;
    private Listener listener;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public PlotlyRenderer(Context ctx) {
        web = new WebView(ctx);
        web.setBackgroundColor(0xFF0E1217);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/viewer/viewer.html");
    }

    /** Called from the page on a WebView thread. */
    private final class Bridge {
        @JavascriptInterface public void ready() {
            ui.post(() -> {
                ready = true;
                for (String js : pending) web.evaluateJavascript(js, null);
                pending.clear();
            });
        }
        @JavascriptInterface public void cell(int r, int c) {
            ui.post(() -> { if (listener != null) listener.onCell(r, c); });
        }
        @JavascriptInterface public void swipe(int dir) {
            ui.post(() -> { if (listener != null) listener.onSwipe(dir); });
        }
    }

    private void run(String js) {
        if (ready) web.evaluateJavascript(js, null);
        else pending.add(js);
    }

    @Override public View view() { return web; }
    @Override public void setListener(Listener l) { listener = l; }
    @Override public void setMode(int mode) { run("jt.setMode(" + mode + ")"); }
    @Override public void setShowHits(boolean on) { run("jt.setHits(" + on + ")"); }
    @Override public void setShowGhost(boolean on) { run("jt.setGhost(" + on + ")"); }
    @Override public void setSliceRow(int row) { run("jt.setSlice(" + row + ")"); }

    @Override public void setDot(int which, double row, double col) {
        run("jt.setDot(" + which + "," + MiniJson.num(row) + "," + MiniJson.num(col) + ")");
    }

    @Override public void setTrail(double[] rows, double[] cols, double[] alphas) {
        run("jt.setTrail(" + arr(rows) + "," + arr(cols) + "," + arr(alphas) + ")");
    }

    @Override public void setTable(CalTable cur, CalTable compare, int[][] hits) {
        run("jt.setTable(" + tableJson(cur, compare, hits) + ")");
    }

    @Override public void destroy() {
        ui.removeCallbacksAndMessages(null);
        web.removeJavascriptInterface("Android");
        web.destroy();
    }

    // ---------- JSON for the page ----------

    private static String arr(double[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) sb.append(i > 0 ? "," : "").append(MiniJson.num(round(a[i])));
        return sb.append(']').toString();
    }

    private static double round(double v) { return Math.round(v * 1000) / 1000.0; }

    private static String matrix(double[][] m) {
        StringBuilder sb = new StringBuilder("[");
        for (int r = 0; r < m.length; r++) {
            sb.append(r > 0 ? "," : "").append('[');
            for (int c = 0; c < m[r].length; c++) sb.append(c > 0 ? "," : "").append(MiniJson.num(m[r][c]));
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    private static String matrix(int[][] m) {
        StringBuilder sb = new StringBuilder("[");
        for (int r = 0; r < m.length; r++) {
            sb.append(r > 0 ? "," : "").append('[');
            for (int c = 0; c < m[r].length; c++) sb.append(c > 0 ? "," : "").append(m[r][c]);
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    private static String labels(double[] axis, int dec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < axis.length; i++) {
            String s = String.format(Locale.US, "%." + Math.max(0, dec) + "f", axis[i]);
            if (dec > 0) s = s.replaceAll("\\.?0+$", "");
            sb.append(i > 0 ? "," : "").append(MiniJson.quote(s));
        }
        return sb.append(']').toString();
    }

    static String tableJson(CalTable t, CalTable ref, int[][] hits) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(MiniJson.quote(t.def.id));
        if (!t.ok()) return sb.append(",\"error\":").append(MiniJson.quote(t.name() + ": " + t.error)).append('}').toString();
        boolean same = ref != null && ref.ok() && ref.rows == t.rows && ref.cols == t.cols && ref.curve == t.curve;
        sb.append(",\"name\":").append(MiniJson.quote(t.name()))
                .append(",\"curve\":").append(t.curve)
                .append(",\"rows\":").append(t.rows).append(",\"cols\":").append(t.cols)
                .append(",\"rowLabels\":").append(labels(t.rowAxis, t.rowDec))
                .append(",\"colLabels\":").append(labels(t.colAxis, t.colDec))
                .append(",\"colVals\":").append(arr(t.colAxis))
                .append(",\"rowTitle\":").append(MiniJson.quote(t.rowUnit)).append(",\"colTitle\":").append(MiniJson.quote(t.colUnit))
                .append(",\"rowUnit\":").append(MiniJson.quote(t.rowUnit)).append(",\"colUnit\":").append(MiniJson.quote(t.colUnit))
                .append(",\"unit\":").append(MiniJson.quote(t.unit)).append(",\"dec\":").append(t.dec)
                .append(",\"vals\":").append(matrix(t.val));
        if (same) {
            sb.append(",\"ref\":").append(matrix(ref.val)).append(",\"refX\":").append(arr(ref.colAxis));
            int[][] chg = new int[t.rows][t.cols];
            for (int r = 0; r < t.rows; r++)
                for (int c = 0; c < t.cols; c++)
                    if (t.raw[r][c] != ref.raw[r][c]) chg[r][c] = t.val[r][c] > ref.val[r][c] ? 1 : -1;
            sb.append(",\"chg\":").append(matrix(chg));
        }
        if (hits != null && !t.curve) sb.append(",\"hits\":").append(matrix(hits));
        return sb.append('}').toString();
    }
}
