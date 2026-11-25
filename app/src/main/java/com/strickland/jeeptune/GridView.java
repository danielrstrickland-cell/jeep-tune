package com.strickland.jeeptune;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.strickland.jeeptune.core.CalTable;
import com.strickland.jeeptune.core.CellInfo;
import com.strickland.jeeptune.core.Ramp;
import com.strickland.jeeptune.core.SurfaceMesh;
import com.strickland.jeeptune.core.Units;

/** Native 2D heat map of a table: coloured cells with their values, pinch zoom, pan, tap, pen hover. Canvas only. */
final class GridView extends View {
    private static final int BG = 0xFF0E1217, FG = 0xFFC9D3DD, REPLAY = 0xFFFF4D4D, LIVE = 0xFF35D0BA;

    private final float dp;
    private final Paint fill = new Paint(), txt = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rc = new RectF();
    private final ScaleGestureDetector scale;
    private final GestureDetector taps;
    private final EdgeSwipe edge;
    private TableRenderer.Listener listener;

    private CalTable t, ref;
    private int[][] hits;
    private boolean showHits;
    private double lo, hi;
    private int maxHits = 1;
    private float zoom = 1, panX, panY;
    private int selR = -1, selC = -1, hovR = -1, hovC = -1;
    private double replayRow = Double.NaN, replayCol = Double.NaN, liveRow = Double.NaN, liveCol = Double.NaN;
    private double[] trR = new double[0], trC = new double[0], trA = new double[0];
    /** Last pen pressure seen on this view (0..1); reserved for the future editor, not used for drawing. */
    float penPressure;

    GridView(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        txt.setTextAlign(Paint.Align.CENTER);
        line.setStyle(Paint.Style.STROKE);
        scale = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float f = d.getScaleFactor(), nz = Math.max(1f, Math.min(8f, zoom * f));
                f = nz / zoom;
                float fx = d.getFocusX() - lm(), fy = d.getFocusY() - tm();
                panX = fx - (fx - panX) * f;
                panY = fy - (fy - panY) * f;
                zoom = nz;
                clampPan();
                invalidate();
                return true;
            }
        });
        taps = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (scale.isInProgress()) return false;
                panX -= dx;
                panY -= dy;
                clampPan();
                invalidate();
                return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                int[] rcl = cellAt(e.getX(), e.getY());
                if (rcl != null) {
                    selR = rcl[0];
                    selC = rcl[1];
                    invalidate();
                    if (listener != null) listener.onCell(selR, selC);
                }
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                zoom = 1;
                panX = 0;
                panY = 0;
                invalidate();
                return true;
            }
        });
        edge = new EdgeSwipe(this, d -> { if (listener != null) listener.onSwipe(d); });
    }

    void setListener(TableRenderer.Listener l) { listener = l; }

    void setTable(CalTable cur, CalTable compare, int[][] h) {
        boolean newShape = t == null || cur == null || t.def != cur.def;
        t = cur;
        ref = cur != null && SurfaceMesh.compatible(cur, compare) ? compare : null;
        hits = h;
        lo = Double.MAX_VALUE;
        hi = -Double.MAX_VALUE;
        maxHits = 1;
        if (cur != null && cur.ok()) {
            for (double[] r : cur.val) for (double v : r) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            if (h != null) for (int[] r : h) for (int x : r) maxHits = Math.max(maxHits, x);
        }
        if (newShape) { selR = -1; selC = -1; hovR = -1; hovC = -1; zoom = 1; panX = 0; panY = 0; }
        invalidate();
    }

    void setShowHits(boolean on) { showHits = on; invalidate(); }

    void setDot(int which, double row, double col) {
        if (which == TableRenderer.REPLAY) { replayRow = row; replayCol = col; }
        else { liveRow = row; liveCol = col; }
        invalidate();
    }

    void setTrail(double[] r, double[] c, double[] a) { trR = r; trC = c; trA = a; invalidate(); }

    // ---------- layout ----------

    private float lm() { return 72 * dp; }
    private float tm() { return 34 * dp; }
    private boolean ready() { return t != null && t.ok() && !t.curve; }
    private float cw() { return (getWidth() - lm()) / t.cols * zoom; }
    private float ch() { return (getHeight() - tm()) / t.rows * zoom; }

    private void clampPan() {
        if (!ready()) return;
        panX = Math.max(Math.min(0, getWidth() - lm() - cw() * t.cols), Math.min(0, panX));
        panY = Math.max(Math.min(0, getHeight() - tm() - ch() * t.rows), Math.min(0, panY));
    }

    private int[] cellAt(float x, float y) {
        if (!ready() || x < lm() || y < tm()) return null;
        int c = (int) Math.floor((x - lm() - panX) / cw()), r = (int) Math.floor((y - tm() - panY) / ch());
        if (c < 0 || r < 0 || c >= t.cols || r >= t.rows) return null;
        return new int[]{r, c};
    }

    // ---------- drawing ----------

    @Override
    protected void onDraw(Canvas cv) {
        cv.drawColor(BG);
        if (!ready()) return;
        float cw = cw(), ch = ch(), left = lm(), top = tm();
        double span = hi - lo;
        cv.save();
        cv.clipRect(left, top, getWidth(), getHeight());
        float ts = Math.min(ch * 0.4f, cw * 0.26f);
        boolean drawText = ts >= 8 * dp;
        txt.setTextSize(Math.min(ts, 18 * dp));
        txt.setColor(FG);
        int c0 = Math.max(0, (int) Math.floor(-panX / cw)), c1 = Math.min(t.cols - 1, (int) Math.floor((getWidth() - left - panX) / cw));
        int r0 = Math.max(0, (int) Math.floor(-panY / ch)), r1 = Math.min(t.rows - 1, (int) Math.floor((getHeight() - top - panY) / ch));
        float gap = Math.max(1f, dp * 0.75f);
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                float x = left + panX + c * cw, y = top + panY + r * ch;
                double f = showHits && hits != null ? Math.sqrt(hits[r][c] / (double) maxHits) : (span <= 0 ? 0 : (t.val[r][c] - lo) / span);
                int col = Ramp.argb(f);
                fill.setColor(col);
                cv.drawRect(x + gap / 2, y + gap / 2, x + cw - gap / 2, y + ch - gap / 2, fill);
                if (drawText) {
                    txt.setColor(f > 0.62 ? 0xFFFFFFFF : 0xFF1A1206);
                    String s = showHits && hits != null ? Integer.toString(hits[r][c]) : Units.format(t.val[r][c], t.unit, t.dec);
                    cv.drawText(s, x + cw / 2, y + ch / 2 + txt.getTextSize() * 0.35f, txt);
                }
                if (ref != null && ref.raw[r][c] != t.raw[r][c]) {
                    line.setStrokeWidth(2.5f * dp);
                    line.setColor(0xFF000000 | (t.val[r][c] > ref.val[r][c] ? Ramp.WARM : Ramp.COOL));
                    rc.set(x + 2 * dp, y + 2 * dp, x + cw - 2 * dp, y + ch - 2 * dp);
                    cv.drawRect(rc, line);
                }
            }
        }
        outline(cv, selR, selC, 0xFFFFFFFF, 2f);
        outline(cv, hovR, hovC, 0xFFFFD27A, 3f);
        drawDots(cv);
        cv.restore();
        drawHeaders(cv, cw, ch, left, top);
    }

    private void outline(Canvas cv, int r, int c, int color, float w) {
        if (r < 0 || c < 0 || r >= t.rows || c >= t.cols) return;
        line.setStrokeWidth(w * dp);
        line.setColor(color);
        float x = lm() + panX + c * cw(), y = tm() + panY + r * ch();
        rc.set(x, y, x + cw(), y + ch());
        cv.drawRect(rc, line);
    }

    private float px(double col) { return (float) (lm() + panX + (col + 0.5) * cw()); }
    private float py(double row) { return (float) (tm() + panY + (row + 0.5) * ch()); }

    private void drawDots(Canvas cv) {
        for (int i = 0; i < trR.length && i < trC.length && i < trA.length; i++) {
            dotP.setColor(REPLAY);
            dotP.setAlpha((int) (255 * Math.max(0, Math.min(1, trA[i])) * 0.7));
            cv.drawCircle(px(trC[i]), py(trR[i]), (3 + 4 * (float) trA[i]) * dp, dotP);
        }
        dot(cv, replayRow, replayCol, REPLAY);
        dot(cv, liveRow, liveCol, LIVE);
    }

    private void dot(Canvas cv, double row, double col, int color) {
        if (Double.isNaN(row) || Double.isNaN(col)) return;
        float x = px(col), y = py(row);
        dotP.setColor(color);
        dotP.setAlpha(70);
        cv.drawCircle(x, y, 16 * dp, dotP);
        dotP.setAlpha(140);
        cv.drawCircle(x, y, 10 * dp, dotP);
        dotP.setColor(0xFFFFFFFF);
        dotP.setAlpha(255);
        cv.drawCircle(x, y, 4 * dp, dotP);
    }

    private void drawHeaders(Canvas cv, float cw, float ch, float left, float top) {
        fill.setColor(BG);
        cv.drawRect(0, 0, getWidth(), top, fill);
        cv.drawRect(0, 0, left, getHeight(), fill);
        txt.setColor(FG);
        txt.setTextSize(Math.min(12 * dp, Math.max(8 * dp, cw * 0.28f)));
        txt.setTextAlign(Paint.Align.CENTER);
        cv.save();
        cv.clipRect(left, 0, getWidth(), top);
        for (int c = 0; c < t.cols; c++) {
            float x = left + panX + (c + 0.5f) * cw;
            if (x < left - cw || x > getWidth() + cw) continue;
            cv.drawText(CellInfo.num(t.colAxis[c], t.colDec), x, top - 8 * dp, txt);
        }
        cv.restore();
        txt.setTextAlign(Paint.Align.RIGHT);
        txt.setTextSize(Math.min(12 * dp, Math.max(8 * dp, ch * 0.4f)));
        cv.save();
        cv.clipRect(0, top, left, getHeight());
        for (int r = 0; r < t.rows; r++) {
            float y = top + panY + (r + 0.5f) * ch;
            if (y < top - ch || y > getHeight() + ch) continue;
            cv.drawText(CellInfo.num(t.rowAxis[r], t.rowDec), left - 8 * dp, y + txt.getTextSize() * 0.35f, txt);
        }
        cv.restore();
        txt.setTextAlign(Paint.Align.LEFT);
        txt.setTextSize(11 * dp);
        txt.setColor(0xFFA3AFBC);
        cv.drawText(t.rowUnit + " ↓  " + t.colUnit + " →", 6 * dp, top - 8 * dp, txt);
        txt.setTextAlign(Paint.Align.CENTER);
    }

    // ---------- touch ----------

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        penPressure = Pen.pressure(ev);
        if (edge.onTouch(ev)) return true;
        scale.onTouchEvent(ev);
        taps.onTouchEvent(ev);
        return true;
    }

    @Override
    public boolean onHoverEvent(MotionEvent ev) {
        if (!Pen.isStylus(ev)) return super.onHoverEvent(ev);
        if (ev.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
            hovR = -1;
            hovC = -1;
            invalidate();
            if (listener != null) listener.onHover(-1, -1, ev.getX(), ev.getY());
            return true;
        }
        int[] rcl = cellAt(ev.getX(), ev.getY());
        int r = rcl == null ? -1 : rcl[0], c = rcl == null ? -1 : rcl[1];
        if (r != hovR || c != hovC) { hovR = r; hovC = c; invalidate(); }
        if (listener != null) listener.onHover(r, c, ev.getX(), ev.getY());
        return true;
    }
}
