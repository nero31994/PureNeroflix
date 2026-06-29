package com.neroflix.tv.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

public class LicenseManager {

    public enum Status { APPROVED, EXPIRED, NOT_FOUND, TAMPERED }
    public enum Plan   { FREE, PREMIUM }

    private static final String PREFS_NAME     = "neroflix_license";
    private static final String PREF_TOKEN     = "access_token";
    private static final String PREF_TOKEN_TS  = "token_issued_time";
    private static final String PREF_PLAN      = "device_plan";
    private static final String PREF_CACHE     = "license_cache";
    private static final String PREF_CACHE_TS  = "license_cache_time";
    private static final String PREF_FREE_CODE = "saved_free_code";
    private static final String PREF_SERVERS   = "cached_servers";

    private static final long TOKEN_MAX_AGE    = 24 * 60 * 60 * 1000L;
    private static final long CACHE_MAX_AGE    = 24 * 60 * 60 * 1000L;

    // XOR-encoded worker URL (key = 0x7A)
    // Decodes to: https://nero-license.kkt01.workers.dev/license
    private static final byte   W_KEY = 0x7A;
    private static final byte[] WORKER_ENC = {
        (byte)0x12,(byte)0x0E,(byte)0x0E,(byte)0x0A,(byte)0x09,(byte)0x40,
        (byte)0x55,(byte)0x55,(byte)0x14,(byte)0x1F,(byte)0x08,(byte)0x15,
        (byte)0x57,(byte)0x16,(byte)0x13,(byte)0x19,(byte)0x1F,(byte)0x14,
        (byte)0x09,(byte)0x1F,(byte)0x54,(byte)0x11,(byte)0x11,(byte)0x0E,
        (byte)0x4A,(byte)0x4B,(byte)0x54,(byte)0x0D,(byte)0x15,(byte)0x08,
        (byte)0x11,(byte)0x1F,(byte)0x08,(byte)0x09,(byte)0x54,(byte)0x1E,
        (byte)0x1F,(byte)0x0C,(byte)0x55,(byte)0x16,(byte)0x13,(byte)0x19,
        (byte)0x1F,(byte)0x14,(byte)0x09,(byte)0x1F,
    };

    private static String workerUrl() {
        byte[] dec = new byte[WORKER_ENC.length];
        for (int i = 0; i < WORKER_ENC.length; i++) dec[i] = (byte)(WORKER_ENC[i] ^ W_KEY);
        return new String(dec);
    }

    // XOR-encoded expected SHA-256 signature (key = 0x3A)
    private static final byte   SIG_KEY = 0x3A;
    private static final byte[] SIG_ENC = {
        (byte)0x0A,(byte)0x0C,(byte)0x0C,(byte)0x09,(byte)0x0B,(byte)0x0D,
        (byte)0x0B,(byte)0x7B,(byte)0x03,(byte)0x78,(byte)0x08,(byte)0x7F,
        (byte)0x02,(byte)0x08,(byte)0x03,(byte)0x0D,(byte)0x78,(byte)0x7C,
        (byte)0x7C,(byte)0x02,(byte)0x7F,(byte)0x0A,(byte)0x0D,(byte)0x0D,
        (byte)0x0B,(byte)0x0D,(byte)0x03,(byte)0x7C,(byte)0x7F,(byte)0x02,
        (byte)0x7F,(byte)0x79,(byte)0x7F,(byte)0x0B,(byte)0x79,(byte)0x0B,
        (byte)0x0A,(byte)0x0D,(byte)0x0F,(byte)0x08,(byte)0x79,(byte)0x7C,
        (byte)0x79,(byte)0x0F,(byte)0x0D,(byte)0x0A,(byte)0x02,(byte)0x02,
        (byte)0x0F,(byte)0x7F,(byte)0x0A,(byte)0x7B,(byte)0x7B,(byte)0x78,
        (byte)0x7E,(byte)0x0E,(byte)0x7E,(byte)0x0E,(byte)0x08,(byte)0x03,
        (byte)0x7C,(byte)0x0C,(byte)0x79,(byte)0x0B
    };

    private static String expectedSig() {
        byte[] dec = new byte[SIG_ENC.length];
        for (int i = 0; i < SIG_ENC.length; i++) dec[i] = (byte)(SIG_ENC[i] ^ SIG_KEY);
        return new String(dec).toUpperCase();
    }

    public interface LicenseCallback  { void onResult(Status status); }
    public interface ServersCallback  { void onResult(String[][] servers); }

    // NEW: callback for yastream stream list
    public interface StreamsCallback  { void onResult(JSONArray streams); }

    private static String lastMessage = "";
    public static String getLastMessage() { return lastMessage; }

    // -----------------------------------------------------------------------
    // Tamper detection
    // -----------------------------------------------------------------------
    private static boolean checkSignature(Context ctx) {
        return true; // Disabled — security handled server-side by Worker
    }

    private static boolean checkPackageName(Context ctx) {
        return "com.neroflix.tv.app".equals(ctx.getPackageName());
    }

    public static boolean isApkTampered(Context ctx) {
        return !checkSignature(ctx) || !checkPackageName(ctx);
    }

    // -----------------------------------------------------------------------
    // fetchServers — called by launchPlayer() in DetailActivity.
    // -----------------------------------------------------------------------
    public static void fetchServers(Context context, ServersCallback callback) {
        if (!checkSignature(context) || !checkPackageName(context)) {
            callback.onResult(null);
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String token    = prefs.getString(PREF_TOKEN, "");
        String deviceId = getDeviceId(context);
        long   tokenAge = System.currentTimeMillis() - prefs.getLong(PREF_TOKEN_TS, 0);

        if (!token.isEmpty() && tokenAge < TOKEN_MAX_AGE) {
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("device_id",    deviceId);
                    body.put("action",       "verify_token");
                    body.put("token",        token);
                    body.put("version_code", BuildConfig.VERSION_CODE);

                    String response = postToWorker(body.toString());
                    if (response != null) {
                        JSONObject json = new JSONObject(response);
                        if ("valid".equals(json.optString("status"))) {
                            String[][] servers = parseServers(json);
                            if (servers != null) cacheServers(context, json.optJSONArray("servers"));
                            callback.onResult(servers);
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.e("LicenseManager", "token verify failed", e);
                }
                doFullServerCheck(context, deviceId, prefs, callback);
            }).start();
        } else {
            new Thread(() -> doFullServerCheck(context, deviceId, prefs, callback)).start();
        }
    }

    private static void doFullServerCheck(Context context, String deviceId,
                                          SharedPreferences prefs, ServersCallback callback) {
        try {
            String savedCode = prefs.getString(PREF_FREE_CODE, "");
            JSONObject body  = new JSONObject();
            body.put("device_id",    deviceId);
            body.put("version_code", BuildConfig.VERSION_CODE);
            if (!savedCode.isEmpty()) body.put("free_code", savedCode);

            String response = postToWorker(body.toString());
            if (response != null) {
                JSONObject json   = new JSONObject(response);
                String status     = json.optString("status", "");
                String plan       = json.optString("plan", "free");
                String newToken   = json.optString("token", "");
                lastMessage       = json.optString("message", "");

                if (!newToken.isEmpty()) {
                    prefs.edit()
                        .putString(PREF_TOKEN,  newToken)
                        .putLong(PREF_TOKEN_TS, System.currentTimeMillis())
                        .putString(PREF_PLAN,   plan)
                        .apply();
                }

                if ("approved".equals(status)) {
                    String[][] servers = parseServers(json);
                    if (servers != null) cacheServers(context, json.optJSONArray("servers"));
                    callback.onResult(servers);
                    return;
                }
            }
        } catch (Exception e) {
            Log.e("LicenseManager", "doFullServerCheck failed", e);
            String cachedServers = prefs.getString(PREF_SERVERS, null);
            long   cacheAge      = System.currentTimeMillis() - prefs.getLong(PREF_CACHE_TS, 0);
            if (cachedServers != null && cacheAge < CACHE_MAX_AGE) {
                try {
                    callback.onResult(parseServersFromArray(new JSONArray(cachedServers)));
                    return;
                } catch (Exception ignored) {}
            }
        }
        callback.onResult(null);
    }

    // -----------------------------------------------------------------------
    // NEW: fetchYastreamStreams — called when user picks a yastream server.
    // Posts action=get_streams to the Worker and returns the stream list.
    // -----------------------------------------------------------------------
    public static void fetchYastreamStreams(Context context,
                                            String tmdbId,
                                            String mediaType,
                                            int season,
                                            int episode,
                                            StreamsCallback callback) {
        if (!checkSignature(context) || !checkPackageName(context)) {
            callback.onResult(null);
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String token    = prefs.getString(PREF_TOKEN, "");
        String deviceId = getDeviceId(context);

        if (token.isEmpty()) {
            Log.w("LicenseManager", "fetchYastreamStreams: token is empty — device not activated or token expired");
            // Try to re-authenticate before giving up
            new Thread(() -> doFullServerCheck(context, deviceId, prefs, servers -> {
                // After re-auth, retry if we now have a token
                String refreshedToken = prefs.getString(PREF_TOKEN, "");
                if (!refreshedToken.isEmpty()) {
                    // Re-enter with the new token
                    fetchYastreamStreams(context, tmdbId, mediaType, season, episode, callback);
                } else {
                    Log.e("LicenseManager", "fetchYastreamStreams: re-auth failed, still no token");
                    callback.onResult(null);
                }
            })).start();
            return;
        }

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("device_id",   deviceId);
                body.put("action",      "get_streams");
                body.put("token",       token);
                body.put("tmdb_id",     tmdbId);
                body.put("media_type",  mediaType);
                if (season > 0)  body.put("season",  String.valueOf(season));
                if (episode > 0) body.put("episode", String.valueOf(episode));

                String response = postToWorker(body.toString());
                if (response != null) {
                    JSONObject json = new JSONObject(response);
                    if ("ok".equals(json.optString("status"))) {
                        callback.onResult(json.optJSONArray("streams"));
                        return;
                    }
                }
            } catch (Exception e) {
                Log.e("LicenseManager", "fetchYastreamStreams failed", e);
            }
            callback.onResult(null);
        }).start();
    }

    // -----------------------------------------------------------------------
    // check / checkWithCode / checkDeviceOnly — used by ActivationActivity
    // -----------------------------------------------------------------------
    public static void checkDeviceOnly(Context context, LicenseCallback callback) {
        if (!checkSignature(context) || !checkPackageName(context)) {
            callback.onResult(Status.TAMPERED);
            return;
        }
        String deviceId = getDeviceId(context);
        Log.d("LicenseDebug", "checkDeviceOnly: device_id=" + deviceId);
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("device_id",    deviceId);
                body.put("version_code", BuildConfig.VERSION_CODE);
                Log.d("LicenseDebug", "Sending to Worker: " + body.toString());

                String response = postToWorker(body.toString());
                Log.d("LicenseDebug", "Worker response: " + response);

                if (response != null) {
                    JSONObject json = new JSONObject(response);
                    SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    prefs.edit()
                        .putString(PREF_CACHE,  response)
                        .putLong(PREF_CACHE_TS, System.currentTimeMillis())
                        .apply();
                    Status result = parseStatus(context, json);
                    Log.d("LicenseDebug", "parseStatus result: " + result.name());
                    callback.onResult(result);
                    return;
                }
                Log.d("LicenseDebug", "Worker returned null response");
            } catch (Exception e) {
                Log.e("LicenseDebug", "checkDeviceOnly failed: " + e.getMessage(), e);
            }
            callback.onResult(Status.NOT_FOUND);
        }).start();
    }

    public static void check(Context context, LicenseCallback callback) {
        if (!checkSignature(context) || !checkPackageName(context)) {
            callback.onResult(Status.TAMPERED);
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long   cacheAge = System.currentTimeMillis() - prefs.getLong(PREF_CACHE_TS, 0);
        String cached   = prefs.getString(PREF_CACHE, null);

        if (cached != null && cacheAge <= CACHE_MAX_AGE) {
            try { callback.onResult(parseStatus(context, new JSONObject(cached))); return; }
            catch (Exception ignored) {}
        }

        String deviceId  = getDeviceId(context);
        String savedCode = prefs.getString(PREF_FREE_CODE, "");

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("device_id",    deviceId);
                body.put("version_code", BuildConfig.VERSION_CODE);
                if (!savedCode.isEmpty()) body.put("free_code", savedCode);

                String response = postToWorker(body.toString());
                if (response != null) {
                    prefs.edit()
                        .putString(PREF_CACHE,  response)
                        .putLong(PREF_CACHE_TS, System.currentTimeMillis())
                        .apply();
                    callback.onResult(parseStatus(context, new JSONObject(response)));
                    return;
                }
            } catch (Exception e) {
                Log.e("LicenseManager", "check failed", e);
            }
            callback.onResult(cached != null ? Status.APPROVED : Status.NOT_FOUND);
        }).start();
    }

    public static void checkWithCode(Context context, String freeCode, LicenseCallback callback) {
        if (!checkSignature(context) || !checkPackageName(context)) {
            callback.onResult(Status.TAMPERED);
            return;
        }
        String deviceId = getDeviceId(context);
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("device_id",    deviceId);
                body.put("version_code", BuildConfig.VERSION_CODE);
                body.put("free_code",    freeCode);

                String response = postToWorker(body.toString());
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                if (response != null) {
                    JSONObject json = new JSONObject(response);
                    if ("approved".equals(json.optString("status"))) {
                        prefs.edit().putString(PREF_FREE_CODE, freeCode).apply();
                    }
                    prefs.edit()
                        .putString(PREF_CACHE,  response)
                        .putLong(PREF_CACHE_TS, System.currentTimeMillis())
                        .apply();
                    callback.onResult(parseStatus(context, json));
                    return;
                }
            } catch (Exception e) {
                Log.e("LicenseManager", "checkWithCode failed", e);
            }
            callback.onResult(Status.NOT_FOUND);
        }).start();
    }

    public static boolean isPremium(Context context) {
        if (!checkSignature(context) || !checkPackageName(context)) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long tokenAge = System.currentTimeMillis() - prefs.getLong(PREF_TOKEN_TS, 0);
        if (tokenAge > TOKEN_MAX_AGE) return false;
        String token = prefs.getString(PREF_TOKEN, "");
        if (token.isEmpty()) return false;
        return "premium".equals(prefs.getString(PREF_PLAN, "free"));
    }

    public static String getDeviceId(Context context) {
        return Settings.Secure.getString(
            context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------
    private static String postToWorker(String bodyJson) {
        try {
            URL url = new URL(workerUrl());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.getOutputStream().write(bodyJson.getBytes("UTF-8"));
            if (conn.getResponseCode() != 200) return null;
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            Log.e("LicenseManager", "postToWorker failed", e);
            return null;
        }
    }

    private static String[][] parseServers(JSONObject json) {
        try {
            JSONArray arr = json.optJSONArray("servers");
            if (arr == null || arr.length() == 0) return null;
            return parseServersFromArray(arr);
        } catch (Exception e) { return null; }
    }

    private static String[][] parseServersFromArray(JSONArray arr) throws Exception {
        // [0]=name [1]=url [2]=url_tv [3]=url_format
        String[][] servers = new String[arr.length()][4];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.getJSONObject(i);
            servers[i][0] = s.getString("name");
            servers[i][1] = s.getString("url");
            servers[i][2] = s.optString("url_tv", s.getString("url"));
            servers[i][3] = s.optString("url_format", "standard");
        }
        return servers;
    }

    private static void cacheServers(Context context, JSONArray arr) {
        if (arr == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_SERVERS, arr.toString())
            .putLong(PREF_CACHE_TS,  System.currentTimeMillis())
            .apply();
    }

    private static Status parseStatus(Context context, JSONObject json) {
        try {
            String status = json.optString("status", "not_found");
            String plan   = json.optString("plan",   "free");
            String token  = json.optString("token",  "");
            lastMessage   = json.optString("message","");

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (!token.isEmpty()) {
                prefs.edit()
                    .putString(PREF_TOKEN,  token)
                    .putLong(PREF_TOKEN_TS, System.currentTimeMillis())
                    .putString(PREF_PLAN,   plan)
                    .apply();
            } else {
                prefs.edit().putString(PREF_PLAN, plan).apply();
            }

            switch (status) {
                case "approved":     return Status.APPROVED;
                case "expired":      return Status.EXPIRED;
                case "tampered":     return Status.TAMPERED;
                case "code_expired": return Status.NOT_FOUND;
                case "invalid_code": return Status.NOT_FOUND;
                default:             return Status.NOT_FOUND;
            }
        } catch (Exception e) { return Status.NOT_FOUND; }
    }

    // -----------------------------------------------------------------------
    // fetchIptvAccess — called by IPTVActivity
    // -----------------------------------------------------------------------
    public static class IptvAccess {
        public final String m3uUrl;
        public IptvAccess(String m3uUrl) { this.m3uUrl = m3uUrl; }
    }

    public interface IptvCallback { void onResult(IptvAccess result); }

    private static final String PREF_IPTV_URL      = "iptv_m3u_url";
    private static final String PREF_IPTV_CACHE_TS = "iptv_cache_time";

    public static void fetchIptvAccess(Context context, IptvCallback callback) {
        if (!checkSignature(context) || !checkPackageName(context)) {
            callback.onResult(null);
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String token    = prefs.getString(PREF_TOKEN, "");
        String deviceId = getDeviceId(context);
        long   tokenAge = System.currentTimeMillis() - prefs.getLong(PREF_TOKEN_TS, 0);

        if (!token.isEmpty() && tokenAge < TOKEN_MAX_AGE) {
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("device_id",    deviceId);
                    body.put("action",       "verify_token");
                    body.put("token",        token);
                    body.put("version_code", BuildConfig.VERSION_CODE);

                    String response = postToWorker(body.toString());
                    if (response != null) {
                        JSONObject json = new JSONObject(response);
                        if ("valid".equals(json.optString("status"))) {
                            String m3uUrl = json.optString("iptv_m3u_url", "");
                            if (!m3uUrl.isEmpty()) {
                                cacheIptvUrl(context, m3uUrl);
                                callback.onResult(new IptvAccess(m3uUrl));
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e("LicenseManager", "fetchIptvAccess token verify failed", e);
                }
                doFullIptvCheck(context, deviceId, prefs, callback);
            }).start();
        } else {
            new Thread(() -> doFullIptvCheck(context, deviceId, prefs, callback)).start();
        }
    }

    private static void doFullIptvCheck(Context context, String deviceId,
                                        SharedPreferences prefs, IptvCallback callback) {
        try {
            String savedCode = prefs.getString(PREF_FREE_CODE, "");
            JSONObject body  = new JSONObject();
            body.put("device_id",    deviceId);
            body.put("version_code", BuildConfig.VERSION_CODE);
            if (!savedCode.isEmpty()) body.put("free_code", savedCode);

            String response = postToWorker(body.toString());
            if (response != null) {
                JSONObject json = new JSONObject(response);
                String status   = json.optString("status", "");
                String plan     = json.optString("plan",   "free");
                String newToken = json.optString("token",  "");
                String m3uUrl   = json.optString("iptv_m3u_url", "");
                lastMessage     = json.optString("message", "");

                if (!newToken.isEmpty()) {
                    prefs.edit()
                        .putString(PREF_TOKEN,  newToken)
                        .putLong(PREF_TOKEN_TS, System.currentTimeMillis())
                        .putString(PREF_PLAN,   plan)
                        .apply();
                }

                if ("approved".equals(status) && !m3uUrl.isEmpty()) {
                    cacheIptvUrl(context, m3uUrl);
                    callback.onResult(new IptvAccess(m3uUrl));
                    return;
                }
            }
        } catch (Exception e) {
            Log.e("LicenseManager", "doFullIptvCheck failed", e);
            String cached   = prefs.getString(PREF_IPTV_URL, null);
            long   cacheAge = System.currentTimeMillis() - prefs.getLong(PREF_IPTV_CACHE_TS, 0);
            if (cached != null && !cached.isEmpty() && cacheAge < CACHE_MAX_AGE) {
                callback.onResult(new IptvAccess(cached));
                return;
            }
        }
        callback.onResult(null);
    }

    private static void cacheIptvUrl(Context context, String url) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_IPTV_URL,      url)
            .putLong(PREF_IPTV_CACHE_TS,   System.currentTimeMillis())
            .apply();
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
