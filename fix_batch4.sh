#!/bin/bash
# fix_batch4.sh — Deep audit critical fixes (issues 1-4)
# Run from repo root: bash fix_batch4.sh
set -e

echo "=== [1/4] Fix #1: DetailActivity — safe loadingDialog dismiss (WindowLeaked) ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/DetailActivity.java", "r") as f:
    src = f.read()

# Replace all bare loadingDialog.dismiss() with safe version
# There are 8 occurrences across 4 dialog sites
old = "                    loadingDialog.dismiss();\n"
new = "                    if (!isFinishing() && !isDestroyed() && loadingDialog.isShowing()) loadingDialog.dismiss();\n"

count = src.count(old)
src = src.replace(old, new)
print(f"  Safe dismiss applied to {count} occurrences")

with open("app/src/main/java/com/neroflix/tv/app/activities/DetailActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [2/4] Fix #2: YastreamPlayerActivity — use volatile isDestroyed flag to guard threads ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java", "r") as f:
    src = f.read()

# Add a volatile destroyed flag field after class opening
old = '    private JSONArray streamList;\n    private int       currentStreamIndex = 0;'
new = '    private JSONArray streamList;\n    private int       currentStreamIndex = 0;\n    private volatile boolean activityDestroyed = false; // guards background threads'

if old in src:
    src = src.replace(old, new, 1)
    print("  Added activityDestroyed flag")
else:
    print("  Field insertion point not found — check manually")

# Guard the subtitle fetch thread runOnUiThread calls
old = (
    '                new Thread(() -> {\n'
    '                    try {\n'
    '                        String stremioId = \"tmdb:\" + tmdbId;\n'
    '                        if (\"series\".equals(mediaType) || \"tv\".equals(mediaType)) {\n'
    '                            stremioId += \":\" + season + \":\" + episode;\n'
    '                        }\n'
    '                        String subsUrl = NEROTIVI + \"/subtitles?type=\"\n'
    '                            + (\"tv\".equals(mediaType) ? \"series\" : mediaType)\n'
    '                            + \"&id=\" + stremioId;\n'
    '                        if (season > 0 && episode > 0) {\n'
    '                            subsUrl += \"&season=\" + season + \"&episode=\" + episode;\n'
    '                        }\n'
    '                        org.json.JSONObject subsJson =\n'
    '                            new org.json.JSONObject(fetchUrl(subsUrl));\n'
    '                        org.json.JSONArray subtitles = subsJson.optJSONArray(\"subtitles\");\n'
    '                        if (subtitles != null && subtitles.length() > 0) {\n'
    '                            for (int i = 0; i < streams.length(); i++) {\n'
    '                                streams.getJSONObject(i).put(\"subtitles\", subtitles);\n'
    '                            }\n'
    '                        }\n'
    '                    } catch (Exception ignored) {}\n'
    '\n'
    '                    runOnUiThread(() -> {\n'
    '                        showLoading(false);\n'
    '                        playStream(0);\n'
    '                    });\n'
    '                }).start();'
)
new = (
    '                new Thread(() -> {\n'
    '                    try {\n'
    '                        String stremioId = \"tmdb:\" + tmdbId;\n'
    '                        if (\"series\".equals(mediaType) || \"tv\".equals(mediaType)) {\n'
    '                            stremioId += \":\" + season + \":\" + episode;\n'
    '                        }\n'
    '                        String subsUrl = NEROTIVI + \"/subtitles?type=\"\n'
    '                            + (\"tv\".equals(mediaType) ? \"series\" : mediaType)\n'
    '                            + \"&id=\" + stremioId;\n'
    '                        if (season > 0 && episode > 0) {\n'
    '                            subsUrl += \"&season=\" + season + \"&episode=\" + episode;\n'
    '                        }\n'
    '                        org.json.JSONObject subsJson =\n'
    '                            new org.json.JSONObject(fetchUrl(subsUrl));\n'
    '                        org.json.JSONArray subtitles = subsJson.optJSONArray(\"subtitles\");\n'
    '                        if (subtitles != null && subtitles.length() > 0) {\n'
    '                            for (int i = 0; i < streams.length(); i++) {\n'
    '                                streams.getJSONObject(i).put(\"subtitles\", subtitles);\n'
    '                            }\n'
    '                        }\n'
    '                    } catch (Exception ignored) {}\n'
    '\n'
    '                    if (!activityDestroyed) runOnUiThread(() -> {\n'
    '                        showLoading(false);\n'
    '                        playStream(0);\n'
    '                    });\n'
    '                }).start();'
)

if old in src:
    src = src.replace(old, new, 1)
    print("  Subtitle thread runOnUiThread guarded")
else:
    print("  Subtitle thread pattern not found — check manually")

# Guard fetchKisskhDirect runOnUiThread calls
src = src.replace(
    '                runOnUiThread(() -> {\n'
    '                        showLoading(false);\n'
    '                        showError(\"No streams available.\\nThis episode may not be on kisskh yet.\");\n'
    '                    });',
    'if (!activityDestroyed) runOnUiThread(() -> {\n'
    '                        showLoading(false);\n'
    '                        showError(\"No streams available.\\nThis episode may not be on kisskh yet.\");\n'
    '                    });'
)

src = src.replace(
    '                final int finalIndex = bestIndex;\n'
    '                runOnUiThread(() -> {\n'
    '                    showLoading(false);\n'
    '                    playStream(finalIndex);\n'
    '                });',
    '                final int finalIndex = bestIndex;\n'
    '                if (!activityDestroyed) runOnUiThread(() -> {\n'
    '                    showLoading(false);\n'
    '                    playStream(finalIndex);\n'
    '                });'
)

src = src.replace(
    '                runOnUiThread(() -> {\n'
    '                    showLoading(false);\n'
    '                    showError(\"Failed to fetch stream: \" + e.getMessage());\n'
    '                });',
    'if (!activityDestroyed) runOnUiThread(() -> {\n'
    '                    showLoading(false);\n'
    '                    showError(\"Failed to fetch stream: \" + e.getMessage());\n'
    '                });'
)

# Set flag in onDestroy
old_destroy = (
    '    @Override protected void onDestroy() { super.onDestroy(); releasePlayer(); hideHandler.removeCallbacks(hideTopBar); }'
)
new_destroy = (
    '    @Override protected void onDestroy() {\n'
    '        activityDestroyed = true;\n'
    '        super.onDestroy();\n'
    '        releasePlayer();\n'
    '        hideHandler.removeCallbacks(hideTopBar);\n'
    '    }'
)

if old_destroy in src:
    src = src.replace(old_destroy, new_destroy, 1)
    print("  onDestroy: activityDestroyed flag set + expanded")
else:
    print("  onDestroy pattern not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [3/4] Fix #3: IPTVActivity — reuse single OkHttpClient instead of rebuilding ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/IPTVActivity.java", "r") as f:
    src = f.read()

# Add a shared OkHttpClient field after the timeHandler field
old = '    private final Handler timeHandler = new Handler(Looper.getMainLooper());'
new = (
    '    private final Handler timeHandler = new Handler(Looper.getMainLooper());\n'
    '    // Shared OkHttpClient — rebuilt only when referrer changes, never on every channel switch\n'
    '    private OkHttpClient sharedOkHttpClient = null;\n'
    '    private String       sharedOkHttpReferrer = null;'
)

if old in src:
    src = src.replace(old, new, 1)
    print("  Shared OkHttpClient field added")
else:
    print("  Field insertion point not found — check manually")

# Replace the setupPlayer method to reuse client when referrer hasn't changed
old_setup = (
    '    private void setupPlayer(String referrer) {\n'
    '        if (player != null) {\n'
    '            player.removeListener(playerListener);\n'
    '            player.stop();\n'
    '            player.release();\n'
    '        }\n'
    '        final String ref = referrer == null ? \"\" : referrer.trim();\n'
    '        OkHttpClient okHttpClient = new OkHttpClient.Builder()\n'
    '            .addInterceptor(chain -> {\n'
    '                okhttp3.Request.Builder rb = chain.request().newBuilder()\n'
    '                    .header(\"User-Agent\", \"Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36\");\n'
    '                if (!ref.isEmpty()) {\n'
    '                    rb.header(\"Referer\", ref);\n'
    '                    rb.header(\"Origin\", ref.replaceAll(\"(https?://[^/]+).*\", \"$1\"));\n'
    '                }\n'
    '                return chain.proceed(rb.build());\n'
    '            })\n'
    '            .build();\n'
    '        DataSource.Factory dsFactory = new OkHttpDataSource.Factory(okHttpClient);\n'
    '        player = new ExoPlayer.Builder(this)\n'
    '            .setMediaSourceFactory(new DefaultMediaSourceFactory(dsFactory))\n'
    '            .build();\n'
    '        player.addListener(playerListener);\n'
    '        playerView.setPlayer(player);\n'
    '        playerView.setUseController(false);\n'
    '    }'
)
new_setup = (
    '    private void setupPlayer(String referrer) {\n'
    '        if (player != null) {\n'
    '            player.removeListener(playerListener);\n'
    '            player.stop();\n'
    '            player.release();\n'
    '        }\n'
    '        final String ref = referrer == null ? \"\" : referrer.trim();\n'
    '        // Reuse the existing OkHttpClient if referrer hasn\'t changed — avoids thread pool leaks\n'
    '        if (sharedOkHttpClient == null || !ref.equals(sharedOkHttpReferrer)) {\n'
    '            sharedOkHttpReferrer = ref;\n'
    '            sharedOkHttpClient = new OkHttpClient.Builder()\n'
    '                .addInterceptor(chain -> {\n'
    '                    okhttp3.Request.Builder rb = chain.request().newBuilder()\n'
    '                        .header(\"User-Agent\", \"Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36\");\n'
    '                    if (!ref.isEmpty()) {\n'
    '                        rb.header(\"Referer\", ref);\n'
    '                        rb.header(\"Origin\", ref.replaceAll(\"(https?://[^/]+).*\", \"$1\"));\n'
    '                    }\n'
    '                    return chain.proceed(rb.build());\n'
    '                })\n'
    '                .build();\n'
    '        }\n'
    '        DataSource.Factory dsFactory = new OkHttpDataSource.Factory(sharedOkHttpClient);\n'
    '        player = new ExoPlayer.Builder(this)\n'
    '            .setMediaSourceFactory(new DefaultMediaSourceFactory(dsFactory))\n'
    '            .build();\n'
    '        player.addListener(playerListener);\n'
    '        playerView.setPlayer(player);\n'
    '        playerView.setUseController(false);\n'
    '    }'
)

if old_setup in src:
    src = src.replace(old_setup, new_setup, 1)
    print("  setupPlayer: OkHttpClient now reused when referrer unchanged")
else:
    print("  setupPlayer pattern not found — check manually")

# Shutdown the client in onDestroy
old_destroy = (
    '    @Override\n'
    '    protected void onDestroy() {\n'
    '        super.onDestroy();\n'
    '        autoHideHandler.removeCallbacksAndMessages(null);\n'
    '        searchDebounceHandler.removeCallbacksAndMessages(null);\n'
    '        pipHideHandler.removeCallbacksAndMessages(null);\n'
    '        timeHandler.removeCallbacksAndMessages(null);\n'
    '        if (player != null) { player.stop(); player.release(); player = null; }\n'
    '    }'
)
new_destroy = (
    '    @Override\n'
    '    protected void onDestroy() {\n'
    '        super.onDestroy();\n'
    '        autoHideHandler.removeCallbacksAndMessages(null);\n'
    '        searchDebounceHandler.removeCallbacksAndMessages(null);\n'
    '        pipHideHandler.removeCallbacksAndMessages(null);\n'
    '        timeHandler.removeCallbacksAndMessages(null);\n'
    '        if (player != null) { player.stop(); player.release(); player = null; }\n'
    '        if (sharedOkHttpClient != null) {\n'
    '            sharedOkHttpClient.dispatcher().executorService().shutdown();\n'
    '            sharedOkHttpClient.connectionPool().evictAll();\n'
    '            sharedOkHttpClient = null;\n'
    '        }\n'
    '    }'
)

if old_destroy in src:
    src = src.replace(old_destroy, new_destroy, 1)
    print("  onDestroy: OkHttpClient properly shut down")
else:
    print("  onDestroy pattern not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/activities/IPTVActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [4/4] Fix #4: SearchActivity — TmdbClient.getInstance() needs context ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/SearchActivity.java", "r") as f:
    src = f.read()

old = 'TmdbClient.getInstance().searchMulti(query, new TmdbClient.MovieListCallback() {'
new = 'TmdbClient.getInstance(this).searchMulti(query, new TmdbClient.MovieListCallback() {'

if old in src:
    src = src.replace(old, new, 1)
    print("  SearchActivity.performSearch: getInstance(this) fixed")
else:
    print("  Pattern not found — may already be fixed")

with open("app/src/main/java/com/neroflix/tv/app/activities/SearchActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "✅ Batch 4 done! Run:"
echo "   git add -A && git commit -m 'Fix batch 4: dialog leak, thread guard, OkHttp reuse, getInstance context' && git push"
