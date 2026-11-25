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
        /** Pen (or mouse) hovering: row/col of the cell and the position in the renderer's view; row < 0 when it leaves. */
        default void onHover(int row, int col, float x, float y) { }
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

    // ---- optional (native renderer only; the web renderer keeps these defaults) ----
    /** Pause/resume with the activity (a GL surface needs it). */
    default void onPause() { }
    default void onResume() { }
    /** True while something moves (replay playing, live dot, tilt): render continuously; false: only when dirty. */
    default void setAnimating(boolean on) { }
    default void resetCamera() { }
    /** True when this renderer can take a tilt offset (orbit from the tablet's motion). */
    default boolean supportsTilt() { return false; }
    /** Extra camera yaw and pitch (radians) from tablet tilt, relative to the pose; 0, 0 clears it. */
    default void setTiltOffset(double yawRad, double pitchRad) { }
}
