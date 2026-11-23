package com.strickland.jeeptune;

import android.app.Application;

import java.io.File;

/** Owns the engine (dongle link + reader thread) so rotating or leaving the screen does not stop a read. */
public final class TuneApp extends Application {
    static TuneEngine engine;

    @Override
    public void onCreate() {
        super.onCreate();
        engine = new TuneEngine(this, new File(getFilesDir(), "reads"));
    }
}
