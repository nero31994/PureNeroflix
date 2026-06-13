package com.neroflix.tv.app.util;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import com.neroflix.tv.app.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String GITHUB_USER = "Sasuw30";
    private static final String GITHUB_REPO = "PureNeroflixv2";
    private static final String TOKEN = "";
    private static final String API_URL =
        "https://api.github.com/repos/" + GITHUB_USER + "/" + GITHUB_REPO + "/releases/latest";
    private static final String CHANNEL_ID = "update_channel";

    public static void check(Activity activity) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = openConn(API_URL);
                int code = conn.getResponseCode();
                if (code != 200) { conn.disconnect(); return; }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                String latestTag = json.getString("tag_name").replaceAll("-.*", "").replace("v", "").trim();
                String currentVersion = BuildConfig.VERSION_NAME.replaceAll("-.*", "").replace("v", "").trim();
                String releaseBody = json.optString("body", "A new version is available.").trim();
                if (releaseBody.isEmpty()) releaseBody = "A new version is available.";

                JSONArray assets = json.getJSONArray("assets");
                String apkUrl = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url");
                        break;
                    }
                }

                if (!isNewer(latestTag, currentVersion) || apkUrl == null) return;

                final String finalApkUrl = apkUrl;
                final String finalTag = latestTag;
                final String finalBody = releaseBody;
                activity.runOnUiThread(() -> showUpdateDialog(activity, finalTag, finalBody, finalApkUrl));

            } catch (Exception e) {
                Log.e(TAG, "Update check failed", e);
            }
        }).start();
    }

    private static void showUpdateDialog(Activity activity, String version, String body, String apkUrl) {
        new AlertDialog.Builder(activity)
            .setTitle("Update Available")
            .setMessage("Version " + version + "\n\n" + body)
            .setPositiveButton("Update Now", (d, w) -> downloadAndInstall(activity, apkUrl))
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show();
    }

    private static void downloadAndInstall(Activity activity, String apkUrl) {
        createNotificationChannel(activity);
        NotificationManager nm = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder notif = new NotificationCompat.Builder(activity, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PureNeroFlix Update")
            .setContentText("Downloading...")
            .setProgress(100, 0, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW);
        nm.notify(1, notif.build());

        Toast.makeText(activity, "Downloading update...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                // Follow redirects manually with auth
                String downloadUrl = apkUrl;
                HttpURLConnection conn = openConn(downloadUrl);
                conn.setInstanceFollowRedirects(false);

                // Follow up to 5 redirects
                for (int i = 0; i < 5; i++) {
                    int respCode = conn.getResponseCode();
                    if (respCode == 301 || respCode == 302 || respCode == 303 || respCode == 307) {
                        String location = conn.getHeaderField("Location");
                        conn.disconnect();
                        // Don't send auth to redirect targets (S3 etc)
                        URL redirectUrl = new URL(location);
                        conn = (HttpURLConnection) redirectUrl.openConnection();
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(15000);
                        conn.setInstanceFollowRedirects(false);
                    } else {
                        break;
                    }
                }

                int respCode = conn.getResponseCode();
                if (respCode != 200) {
                    conn.disconnect();
                    new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(activity, "Download failed: HTTP " + respCode, Toast.LENGTH_LONG).show());
                    nm.cancel(1);
                    return;
                }

                int total = conn.getContentLength();
                InputStream is = conn.getInputStream();
                File outFile = new File(activity.getExternalFilesDir(null), "update.apk");
                FileOutputStream fos = new FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int read;
                int downloaded = 0;
                while ((read = is.read(buf)) != -1) {
                    fos.write(buf, 0, read);
                    downloaded += read;
                    if (total > 0) {
                        int progress = (int) ((downloaded * 100L) / total);
                        notif.setProgress(100, progress, false)
                             .setContentText("Downloading... " + progress + "%");
                        nm.notify(1, notif.build());
                    }
                }
                fos.close();
                is.close();
                conn.disconnect();

                notif.setProgress(0, 0, false)
                     .setContentText("Download complete. Installing...")
                     .setOngoing(false)
                     .setSmallIcon(android.R.drawable.stat_sys_download_done);
                nm.notify(1, notif.build());

                activity.runOnUiThread(() -> installApk(activity, outFile));

            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(activity, "Download error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                nm.cancel(1);
            }
        }).start();
    }

    private static void installApk(Activity activity, File apk) {
        if (!apk.exists()) return;
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apk);
        } else {
            uri = Uri.fromFile(apk);
        }
        activity.startActivity(new Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static HttpURLConnection openConn(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("Authorization", TOKEN);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "PureNeroflix-App");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        return conn;
    }

    private static boolean isNewer(String latest, String current) {
        try {
            String[] l = latest.split("\\.");
            String[] c = current.split("\\.");
            int len = Math.max(l.length, c.length);
            for (int i = 0; i < len; i++) {
                int lv = i < l.length ? Integer.parseInt(l[i].trim()) : 0;
                int cv = i < c.length ? Integer.parseInt(c[i].trim()) : 0;
                if (lv > cv) return true;
                if (lv < cv) return false;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void createNotificationChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "App Updates", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE))
                .createNotificationChannel(channel);
        }
    }
}
