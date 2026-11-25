package com.strickland.jeeptune;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.strickland.jeeptune.core.CalTable;
import com.strickland.jeeptune.core.CellInfo;
import com.strickland.jeeptune.core.SurfaceMesh;
import com.strickland.jeeptune.core.Units;

import java.util.ArrayList;
import java.util.List;

/**
 * Axis labels for the 3D view: every string is drawn once with Canvas into one bitmap (an atlas, one string per band);
 * the GL thread uploads it as a texture and draws a small quad at each label's projected anchor.
 */
final class LabelSet {
    static final int CENTER = 0, RIGHT = 1, LEFT = 2;

    static final class Label {
        double x, y, z;          // anchor in world space
        int align;
        float dx, dy;            // pixel offset from the anchor (y up)
        int w, h;                // size in pixels
        float u0, v0, u1, v1;
        String text;
    }

    final List<Label> labels = new ArrayList<>();
    Bitmap atlas;

    static LabelSet build(CalTable t, SurfaceMesh m, float density) {
        LabelSet s = new LabelSet();
        float size = 12 * density;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(0xFFFFFFFF);
        double xl = m.x[0], zf = m.z[0], zb = m.z[m.rows - 1];
        for (int c = 0; c < m.cols; c++)
            s.add(CellInfo.num(t.colAxis[c], t.colDec), m.x[c], 0, zf, CENTER, 0, -14 * density);
        s.add(axisTitle(t.colUnit, true), 0, 0, zf, CENTER, 0, -34 * density);
        int step = m.rows > 10 ? 2 : 1;
        for (int r = 0; r < m.rows; r += step)
            s.add(CellInfo.num(t.rowAxis[r], t.rowDec), xl, 0, m.z[r], RIGHT, -8 * density, 0);
        s.add(axisTitle(t.rowUnit, false), xl, 0, 0, RIGHT, -52 * density, 0);
        String u = t.unit == null ? "" : t.unit;
        s.add(Units.format(m.hi, t.unit, t.dec) + " " + u, xl, SurfaceMesh.HEIGHT, zb, RIGHT, -8 * density, 0);
        s.add(Units.format(m.lo, t.unit, t.dec) + " " + u, xl, 0, zb, RIGHT, -8 * density, 0);
        // atlas: one band per label
        int w = 8, h = 0;
        Paint.FontMetrics fm = p.getFontMetrics();
        int band = (int) Math.ceil(fm.descent - fm.ascent) + 4;
        for (Label l : s.labels) w = Math.max(w, (int) Math.ceil(p.measureText(l.text)) + 4);
        h = band * s.labels.size();
        s.atlas = Bitmap.createBitmap(w, Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(s.atlas);
        int y = 0;
        for (Label l : s.labels) {
            cv.drawText(l.text, 2, y + 2 - fm.ascent, p);
            l.w = (int) Math.ceil(p.measureText(l.text)) + 4;
            l.h = band;
            l.u0 = 0;
            l.u1 = l.w / (float) w;
            l.v0 = y / (float) h;
            l.v1 = (y + band) / (float) h;
            y += band;
        }
        return s;
    }

    private static String axisTitle(String unit, boolean col) {
        if (unit == null) return "";
        if (unit.equals("kPa")) return "MAP kPa";
        return unit;
    }

    private void add(String text, double x, double y, double z, int align, float dx, float dy) {
        Label l = new Label();
        l.text = text;
        l.x = x;
        l.y = y;
        l.z = z;
        l.align = align;
        l.dx = dx;
        l.dy = dy;
        labels.add(l);
    }
}
