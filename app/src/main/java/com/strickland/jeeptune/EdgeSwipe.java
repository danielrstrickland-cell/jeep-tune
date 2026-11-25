package com.strickland.jeeptune;

import android.view.MotionEvent;
import android.view.View;

/** Swipe that starts at the left or right screen edge of a view and moves inward: changes table. */
final class EdgeSwipe {
    interface Fire { void swipe(int dir); }

    private final View view;
    private final Fire fire;
    private final float edge, need;
    private boolean active, fired;
    private float startX;
    private int side;      // -1 left edge, +1 right edge

    EdgeSwipe(View v, Fire f) {
        view = v;
        fire = f;
        float d = v.getResources().getDisplayMetrics().density;
        edge = 28 * d;
        need = 80 * d;
    }

    /** True when this event belongs to an edge swipe (the caller should not treat it as another gesture). */
    boolean onTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                active = false;
                fired = false;
                if (ev.getX() < edge) { active = true; side = -1; }
                else if (ev.getX() > view.getWidth() - edge) { active = true; side = 1; }
                startX = ev.getX();
                return active;
            case MotionEvent.ACTION_POINTER_DOWN:
                active = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (active && !fired) {
                    float dx = ev.getX() - startX;
                    if (side < 0 && dx > need) { fired = true; fire.swipe(-1); }
                    else if (side > 0 && dx < -need) { fired = true; fire.swipe(1); }
                }
                return active;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean was = active;
                active = false;
                return was;
            default:
                return active;
        }
    }
}
