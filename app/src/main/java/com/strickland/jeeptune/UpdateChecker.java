package com.strickland.jeeptune;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;

import com.strickland.jeeptune.core.ReleaseTag;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Looks at the newest GitHub release; the only thing this app ever goes online for. Silent when offline. */
final class UpdateChecker {
    private UpdateChecker() { }

    private static final String LATEST = "https://api.github.com/repos/danielrstrickland-cell/jeep-tune/releases/latest";
    private static final String ASSET = "jeep-tune.apk";

    /** @param manual true when the driver asked: then "up to date" and "couldn't check" are shown too */
    static void check(final Activity a, final long myBuild, final boolean manual) {
        Thread t = new Thread(() -> {
            String tag = null, apk = null;
            boolean reached = false;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(LATEST).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "JeepTune");
                if (c.getResponseCode() == 200) {
                    JSONObject o = new JSONObject(read(c.getInputStream()));
                    tag = o.optString("tag_name", null);
                    JSONArray assets = o.optJSONArray("assets");
                    for (int i = 0; assets != null && i < assets.length(); i++) {
                        JSONObject as = assets.getJSONObject(i);
                        if (ASSET.equals(as.optString("name"))) apk = as.optString("browser_download_url", null);
                    }
                    reached = true;
                }
            } catch (Exception ignored) {
                // offline or GitHub unreachable: say nothing unless asked
            } finally {
                if (c != null) c.disconnect();
            }
            final String fTag = tag, fApk = apk;
            final boolean fReached = reached;
            a.runOnUiThread(() -> report(a, myBuild, manual, fReached, fTag, fApk));
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private static void report(Activity a, long myBuild, boolean manual, boolean reached, String tag, String apk) {
        if (a.isFinishing() || a.isDestroyed()) return;
        boolean newer = reached && tag != null && apk != null && ReleaseTag.isNewer(tag, (int) myBuild);
        if (newer) {
            final String url = apk;
            new AlertDialog.Builder(a)
                    .setTitle("Update available (build " + ReleaseTag.build(tag) + ")")
                    .setMessage("You have build " + myBuild + ". Download the new one, then open it to install over this one. Your saved reads stay.")
                    .setPositiveButton("Download", (d, w) -> {
                        try {
                            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        } catch (RuntimeException ignored) { }
                    })
                    .setNegativeButton("Later", null)
                    .show();
        } else if (manual) {
            new AlertDialog.Builder(a)
                    .setTitle("Updates")
                    .setMessage(reached ? "Build " + myBuild + " is the newest." : "Couldn't reach GitHub. Check the tablet's internet connection.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
