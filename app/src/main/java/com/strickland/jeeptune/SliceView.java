package com.strickland.jeeptune;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import com.strickland.jeeptune.core.CalTable;
import com.strickland.jeeptune.core.CellInfo;
import com.strickland.jeeptune.core.SurfaceMesh;
import com.strickland.jeeptune.core.Units;

/** Native line chart: one rpm row of a table (value against MAP), or a curve. The compare table is a dashed grey line. */
final class SliceView extends View {
    private static final int BG = 0xFF0E1217, FG = 0xFFC9D3DD, AMBER = 0xFFBA7517, GRID = 0xFF26303B;

    private final float dp;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final EdgeSwipe edge;
    private TableRenderer.Listener listener;
    private CalTable t, ref;
    private int row, sel = -1, hov = -1;
    private double xmin, xmax, ymin, ymax;

    SliceView(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        tp.setTextSize(11 * dp);
        edge = new EdgeSwipe(this, d -> { if (listener != null) listener.onSwipe(d); });
    }

    void setListener(TableRenderer.Listener l) { listener = l; }

    void setTable(CalTable cur, CalTable compare) {
        t = cur;
        ref = cur != null && SurfaceMesh.compatible(cur, compare) ? compare : null;
        sel = -1;
        hov = -1;
        recompute();
    }

    void setRow(int r) { row = r; recompute(); }

    private int rowIdx() { return t == null ? 0 : Math.max(0, Math.min(t.rows - 1, t.curve ? 0 : row)); }

    private void recompute() {
        if (t == null || !t.ok()) { invalidate(); return; }
        int r = rowIdx();
        xmin = Double.MAX_VALUE; xmax = -Double.MAX_VALUE; ymin = Double.MAX_VALUE; ymax = -Double.MAX_VALUE;
        for (int c = 0; c < t.cols; c++) {
            xmin = Math.min(xmin, t.colAxis[c]);
            xmax = Math.max(xmax, t.colAxis[c]);
            ymin = Math.min(ymin, t.val[r][c]);
            ymax = Math.max(ymax, t.val[r][c]);
            if (ref != null) { ymin = Math.min(ymin, ref.val[r][c]); ymax = Math.max(ymax, ref.val[r][c]); }
        }
        if (xmax <= xmin) { xmin = 0; xmax = Math.max(1, t.cols - 1); }
        double pad = (ymax - ymin) * 0.1;
        if (pad <= 0) pad = 1;
        ymin -= pad;
        ymax += pad;
        invalidate();
    }

    private float left() { return 72 * dp; }
    private float right() { return getWidth() - 24 * dp; }
    private float top() { return 24 * dp; }
    private float bottom() { return getHeight() - 48 * dp; }
    private float sx(double x) { return (float) (left() + (x - xmin) / (xmax - xmin) * (right() - left())); }
    private float sy(double y) { return (float) (bottom() - (y - ymin) / (ymax - ymin) * (bottom() - top())); }

    @Override
    protected void onDraw(Canvas cv) {
        cv.drawColor(BG);
        if (t == null || !t.ok()) return;
        int r = rowIdx();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(GRID);
        tp.setColor(FG);
        tp.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 4; i++) {
            double y = ymin + (ymax - ymin) * i / 4;
            float yy = sy(y);
            cv.drawLine(left(), yy, right(), yy, p);
            cv.drawText(Units.format(y, t.unit, t.dec), left() - 6 * dp, yy + 4 * dp, tp);
        }
        tp.setTextAlign(Paint.Align.CENTER);
        int every = Math.max(1, t.cols / 12);
        for (int c = 0; c < t.cols; c += every) {
            float xx = sx(t.colAxis[c]);
            cv.drawLine(xx, top(), xx, bottom(), p);
            cv.drawText(CellInfo.num(t.colAxis[c], t.colDec), xx, bottom() + 16 * dp, tp);
        }
        tp.setColor(0xFFA3AFBC);
        cv.drawText(t.colUnit, (left() + right()) / 2, getHeight() - 8 * dp, tp);
        tp.setTextAlign(Paint.Align.LEFT);
        cv.drawText(t.unit + (t.curve || !t.rowKnown ? "" : "   @ " + CellInfo.num(t.rowAxis[r], t.rowDec) + " " + t.rowUnit), left(), top() - 8 * dp, tp);
        if (ref != null) {
            p.setColor(0xFF7F9BB5);
            p.setStrokeWidth(2 * dp);
            p.setPathEffect(new DashPathEffect(new float[]{10 * dp, 6 * dp}, 0));
            line(cv, ref, r);
            p.setPathEffect(null);
        }
        p.setColor(AMBER);
        p.setStrokeWidth(3 * dp);
        line(cv, t, r);
        p.setStyle(Paint.Style.FILL);
        for (int c = 0; c < t.cols; c++) {
            boolean changed = ref != null && ref.raw[r][c] != t.raw[r][c];
            p.setColor(c == hov || c == sel ? 0xFFFFFFFF : changed ? (t.val[r][c] > ref.val[r][c] ? 0xFFE8590C : 0xFF2F8FE0) : AMBER);
            cv.drawCircle(sx(t.colAxis[c]), sy(t.val[r][c]), (c == hov || c == sel ? 6 : 4) * dp, p);
        }
    }

    private void line(Canvas cv, CalTable q, int r) {
        path.reset();
        for (int c = 0; c < q.cols; c++) {
            float x = sx(t.colAxis[c]), y = sy(q.val[r][c]);
            if (c == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        cv.drawPath(path, p);
    }

    private int nearest(float x) {
        if (t == null || !t.ok()) return -1;
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int c = 0; c < t.cols; c++) {
            float d = Math.abs(sx(t.colAxis[c]) - x);
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (edge.onTouch(ev)) return true;
        if (ev.getActionMasked() == MotionEvent.ACTION_UP && t != null && t.ok()) {
            sel = nearest(ev.getX());
            invalidate();
            if (listener != null) listener.onCell(rowIdx(), sel);
        }
        return true;
    }

    @Override
    public boolean onHoverEvent(MotionEvent ev) {
        if (!Pen.isStylus(ev)) return super.onHoverEvent(ev);
        if (ev.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
            hov = -1;
            invalidate();
            if (listener != null) listener.onHover(-1, -1, ev.getX(), ev.getY());
            return true;
        }
        int c = nearest(ev.getX());
        if (c != hov) { hov = c; invalidate(); }
        if (listener != null) listener.onHover(rowIdx(), c, ev.getX(), ev.getY());
        return true;
    }
}
