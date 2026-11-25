package com.strickland.jeeptune;

import android.view.View;

import com.strickland.jeeptune.core.CalTable;

/**
 * Draws one table (grid heat map, 3D surface, slice line chart) and the dots on it. Everything it is given comes from
 * :core (decoded tables, compare, hit counts, replay position), so another renderer (for example native OpenGL ES for
 * the 3D view) can replace {@link PlotlyRenderer} without touching the screen logic. Positions are fractional table
 * indices (row 0..rows-1, col 0..cols-1); NaN hides a dot.
 */
public interface TableRenderer {
    int GRID = 0, SURFACE = 1, SLICE = 2;
    int REPLAY = 0, LIVE = 1;

    interface Listener {
        void onCell(int row, int col);
        /** +1 = swiped left (next table), -1 = swiped right (previous). */
        void onSwipe(int dir);
    }

    View view();
    void setListener(Listener l);
    /** compare and hits may be null. A table with an error shows its message. */
    void setTable(CalTable cur, CalTable compare, int[][] hits);
    void setMode(int mode);
    void setShowHits(boolean on);
    void setShowGhost(boolean on);
    void setSliceRow(int row);
    void setDot(int which, double row, double col);
    void setTrail(double[] rows, double[] cols, double[] alphas);
    void destroy();
}
