package com.strickland.jeeptune;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.strickland.jeeptune.core.CalTable;
import com.strickland.jeeptune.core.SurfaceMesh;

/**
 * {@link TableRenderer} with no WebView: a native Canvas heat map ({@link GridView}), an OpenGL ES surface
 * ({@link GlView}) and a Canvas line chart ({@link SliceView}), switched by mode. Curves always use the line chart.
 */
public final class GlRenderer implements TableRenderer {
    private final Context ctx;
    private final FrameLayout root;
    private final GridView grid;
    private final GlView gl;
    private final SliceView slice;
    private final TextView message;
    private TableRenderer.Listener listener;

    private CalTable cur, ref;
    private int[][] hits;
    private int mode = GRID;
    private boolean showHits, ghost;

    public GlRenderer(Context ctx) {
        this.ctx = ctx;
        root = new FrameLayout(ctx);
        grid = new GridView(ctx);
        gl = new GlView(ctx);
        slice = new SliceView(ctx);
        message = new TextView(ctx);
        message.setTextColor(0xFFA3AFBC);
        message.setTextSize(15);
        message.setGravity(Gravity.CENTER);
        message.setPadding(40, 40, 40, 40);
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        root.addView(gl, new FrameLayout.LayoutParams(-1, -1));
        root.addView(slice, new FrameLayout.LayoutParams(-1, -1));
        root.addView(message, new FrameLayout.LayoutParams(-1, -1));
        show();
    }

    @Override public View view() { return root; }

    @Override public void setListener(Listener l) {
        listener = l;
        grid.setListener(l);
        gl.setListener(l);
        slice.setListener(l);
    }

    private boolean isGrid() { return cur != null && cur.ok() && !cur.curve && cur.rows >= 2 && cur.cols >= 2; }

    private void show() {
        boolean g = isGrid(), c = cur != null && cur.ok() && cur.curve;
        grid.setVisibility(g && mode == GRID ? View.VISIBLE : View.GONE);
        gl.setVisibility(g && mode == SURFACE ? View.VISIBLE : View.INVISIBLE);
        slice.setVisibility(c || (g && mode == SLICE) ? View.VISIBLE : View.GONE);
        message.setVisibility(cur == null || !cur.ok() ? View.VISIBLE : View.GONE);
        if (cur == null) message.setText("Open a saved read or pick a .bin to see this table.");
        else if (!cur.ok()) message.setText(cur.name() + ": " + cur.error);
    }

    private void rebuildMesh(boolean newTable) {
        if (!isGrid()) { gl.setMesh(null, null, newTable); return; }
        boolean cmp = SurfaceMesh.compatible(cur, ref);
        SurfaceMesh m = SurfaceMesh.build(cur, cmp ? ref : null, hits, showHits && hits != null, cmp);
        gl.setMesh(m, LabelSet.build(cur, m, ctx.getResources().getDisplayMetrics().density), newTable);
        gl.setGhost(ghost && cmp);
    }

    @Override public void setTable(CalTable c, CalTable compare, int[][] h) {
        boolean newTable = cur == null || c == null || cur.def != c.def;
        cur = c;
        ref = compare;
        hits = h;
        grid.setTable(c, compare, h);
        slice.setTable(c, compare);
        rebuildMesh(newTable);
        show();
    }

    @Override public void setMode(int m) { mode = m; show(); }

    @Override public void setShowHits(boolean on) {
        showHits = on;
        grid.setShowHits(on);
        rebuildMesh(false);
    }

    @Override public void setShowGhost(boolean on) {
        ghost = on;
        gl.setGhost(on && SurfaceMesh.compatible(cur, ref));
    }

    @Override public void setSliceRow(int row) { slice.setRow(row); }

    @Override public void setDot(int which, double row, double col) {
        grid.setDot(which, row, col);
        if (which == REPLAY) gl.setReplay(row, col); else gl.setLive(row, col);
    }

    @Override public void setTrail(double[] rows, double[] cols, double[] alphas) {
        grid.setTrail(rows, cols, alphas);
        gl.setTrail(rows, cols, alphas);
    }

    @Override public void onPause() { gl.onPause(); }
    @Override public void onResume() { gl.onResume(); }
    @Override public void setAnimating(boolean on) { gl.setExternalAnimating(on); }
    @Override public void resetCamera() { gl.resetCamera(); }
    @Override public boolean supportsTilt() { return true; }
    @Override public void setTiltOffset(double yaw, double pitch) { gl.setTilt(yaw, pitch); }

    @Override public void destroy() {
        gl.setListener(null);
        grid.setListener(null);
        slice.setListener(null);
        gl.onPause();
    }
}
