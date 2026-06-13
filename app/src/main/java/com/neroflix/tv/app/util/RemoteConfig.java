package com.neroflix.tv.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import androidx.appcompat.app.AlertDialog;

import com.neroflix.tv.app.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class RemoteConfig {

    private static final String TAG          = "RemoteConfig";
    private static final String PREFS        = "nero_prefs";
    private static final String KEY_M3U_URL  = "remote_m3u_url";
    private static final String KEY_MIN_VER  = "remote_min_version";

    // XOR key shared with LicenseManager
    private static final byte W_KEY = 0x7A;

    // XOR obfuscated config URL (key = 0x7A)
    // Decodes to: https://raw.githubusercontent.com/Sasuw30/PureNeroflixv2/refs/heads/main/config.json
    private static final byte[] CONFIG_ENC = {
        (byte)0x12,(byte)0x0E,(byte)0x0E,(byte)0x0A,(byte)0x09,(byte)0x40,
        (byte)0x55,(byte)0x55,(byte)0x08,(byte)0x1B,(byte)0x0D,(byte)0x54,
        (byte)0x1D,(byte)0x13,(byte)0x0E,(byte)0x12,(byte)0x0F,(byte)0x18,
        (byte)0x0F,(byte)0x09,(byte)0x1F,(byte)0x08,(byte)0x19,(byte)0x15,
        (byte)0x14,(byte)0x0E,(byte)0x1F,(byte)0x14,(byte)0x0E,(byte)0x54,
        (byte)0x19,(byte)0x15,(byte)0x17,(byte)0x55,(byte)0x29,(byte)0x1B,
        (byte)0x09,(byte)0x0F,(byte)0x0D,(byte)0x49,(byte)0x4A,(byte)0x55,
        (byte)0x2A,(byte)0x0F,(byte)0x08,(byte)0x1F,(byte)0x34,(byte)0x1F,
        (byte)0x08,(byte)0x15,(byte)0x1C,(byte)0x16,(byte)0x13,(byte)0x02,
        (byte)0x0C,(byte)0x48,(byte)0x55,(byte)0x08,(byte)0x1F,(byte)0x1C,
        (byte)0x09,(byte)0x55,(byte)0x12,(byte)0x1F,(byte)0x1B,(byte)0x1E,
        (byte)0x09,(byte)0x55,(byte)0x17,(byte)0x1B,(byte)0x13,(byte)0x14,
        (byte)0x55,(byte)0x19,(byte)0x15,(byte)0x14,(byte)0x1C,(byte)0x13,
        (byte)0x1D,(byte)0x54,(byte)0x10,(byte)0x09,(byte)0x15,(byte)0x14,
    };

    // XOR obfuscated fallback M3U Worker URL (key = 0x7A)
    // Decodes to: https://nero-m3u.kkt01.workers.dev
    private static final byte[] M3U_ENC = {
        (byte)0x12,(byte)0x0E,(byte)0x0E,(byte)0x0A,(byte)0x09,(byte)0x40,
        (byte)0x55,(byte)0x55,(byte)0x14,(byte)0x1F,(byte)0x08,(byte)0x15,
        (byte)0x57,(byte)0x17,(byte)0x49,(byte)0x0F,(byte)0x54,(byte)0x11,
        (byte)0x11,(byte)0x0E,(byte)0x4A,(byte)0x4B,(byte)0x54,(byte)0x0D,
        (byte)0x15,(byte)0x08,(byte)0x11,(byte)0x1F,(byte)0x08,(byte)0x09,
        (byte)0x54,(byte)0x1E,(byte)0x1F,(byte)0x0C,
    };

    private static String decode(byte[] enc) {
        byte[] dec = new byte[enc.length];
        for (int i = 0; i < enc.length; i++)
            dec[i] = (byte)(enc[i] ^ W_KEY);
        return new String(dec);
    }

    public interface Callback {
        void onLoaded(String m3uUrl);
    }

    public static void fetch(Context context, Callback callback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cached = prefs.getString(KEY_M3U_URL, decode(M3U_ENC));
        callback.onLoaded(cached);

        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(decode(CONFIG_ENC)).openConnection();
                conn.setRequestProperty("User-Agent", "PureNeroflix-App");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                if (conn.getResponseCode() != 200) { conn.disconnect(); return; }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                String m3uUrl = json.optString("m3u_url", decode(M3U_ENC));
                if (!m3uUrl.isEmpty()) {
                    prefs.edit().putString(KEY_M3U_URL, m3uUrl).apply();
                }
                int minVersion = json.optInt("min_version", 0);
                prefs.edit().putInt(KEY_MIN_VER, minVersion).apply();

            } catch (Exception e) {
                Log.e(TAG, "RemoteConfig fetch failed", e);
            }
        }).start();
    }

    public static String getCachedM3uUrl(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_M3U_URL, decode(M3U_ENC));
    }

    public static void enforceMinVersion(Activity activity) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(decode(CONFIG_ENC)).openConnection();
                conn.setRequestProperty("User-Agent", "PureNeroflix-App");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                if (conn.getResponseCode() != 200) { conn.disconnect(); return; }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject json    = new JSONObject(sb.toString());
                int minVersion     = json.optInt("min_version", 0);
                int currentVersion = BuildConfig.VERSION_CODE;

                if (currentVersion < minVersion) {
                    activity.runOnUiThread(() ->
                        new AlertDialog.Builder(activity)
                            .setTitle("Update Required")
                            .setMessage("This version is no longer supported. Please update to continue using PureNeroFlix.")
                            .setPositiveButton("Update Now", (d, w) -> {
                                activity.startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/Sasuw30/PureNeroflixv2/releases/latest")));
                                activity.finish();
                            })
                            .setCancelable(false)
                            .show());
                }

            } catch (Exception e) {
                Log.e(TAG, "enforceMinVersion failed", e);
            }
        }).start();
    }
}
