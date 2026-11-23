package com.strickland.jeeptune;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.strickland.jeeptune.core.ReadJob;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Copies a job's files to Downloads/JeepTune/ through MediaStore. */
final class Saver {
    private Saver() { }

    static String save(Context c, ReadJob job) throws IOException {
        if (Build.VERSION.SDK_INT < 29) throw new IOException("Saving to Downloads needs Android 10 or newer");
        job.flush();
        copy(c, new File(job.dir, "flash.bin"), "jeeptune-" + job.id + ".bin", "application/octet-stream");
        copy(c, new File(job.dir, "log.txt"), "jeeptune-" + job.id + "-log.txt", "text/plain");
        copy(c, new File(job.dir, "progress.json"), "jeeptune-" + job.id + "-progress.json", "application/json");
        return "Saved to Downloads/JeepTune/jeeptune-" + job.id + ".bin";
    }

    private static void copy(Context c, File src, String name, String mime) throws IOException {
        if (!src.exists()) {
            if (name.endsWith("-log.txt")) return;
            throw new IOException("missing " + src.getName());
        }
        ContentResolver r = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/JeepTune");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new IOException("could not create " + name);
        try (InputStream in = new FileInputStream(src); OutputStream out = r.openOutputStream(u)) {
            if (out == null) throw new IOException("could not open " + name);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        r.update(u, v, null, null);
    }
}
