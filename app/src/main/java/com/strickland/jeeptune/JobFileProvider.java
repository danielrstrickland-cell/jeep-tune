package com.strickland.jeeptune;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/** Read-only file provider for a job's flash.bin, log.txt and summary.txt (share sheet). */
public final class JobFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.strickland.jeeptune.files";
    static volatile File root;

    private File resolve(Uri u) throws FileNotFoundException {
        List<String> p = u.getPathSegments();
        File r = root;
        if (r == null || p.size() != 2) throw new FileNotFoundException();
        String id = p.get(0), name = p.get(1);
        if (id.contains("/") || id.contains("..") || !(name.equals("flash.bin") || name.equals("log.txt") || name.equals("summary.txt")))
            throw new FileNotFoundException();
        File f = new File(new File(r, id), name);
        if (!f.exists()) throw new FileNotFoundException();
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] proj, String sel, String[] args, String order) {
        try {
            File f = resolve(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{"jeeptune-" + uri.getPathSegments().get(0) + "-" + f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
