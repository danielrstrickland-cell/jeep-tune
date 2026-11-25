package com.strickland.jeeptune;

import android.view.MotionEvent;

/** Small helpers for stylus events. Pressure is read but not used yet (reserved for the future editor). */
final class Pen {
    private Pen() { }

    static boolean isStylus(MotionEvent ev) {
        return ev.getPointerCount() > 0 && ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS;
    }

    /** Pen pressure 0..1 (hover gives 0). Reserved for the editor; nothing reads it for drawing yet. */
    static float pressure(MotionEvent ev) { return isStylus(ev) ? ev.getPressure() : 0f; }
}
