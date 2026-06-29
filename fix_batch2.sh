#!/bin/bash
# fix_batch2.sh — Medium issue fixes (issues 5-9)
# Run from repo root: bash fix_batch2.sh
set -e

echo "=== [1/4] Fix #5: SplashActivity — reduce 10s delay to 2s ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/SplashActivity.java", "r") as f:
    src = f.read()

old = "), 10000);"
new = "), 2000);"

if old in src:
    src = src.replace(old, new, 1)
    print("  Splash delay: 10000ms → 2000ms")
else:
    print("  Pattern not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/activities/SplashActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [2/4] Fix #6: IPTVActivity.loadChannels — add error boundary to raw Thread ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/IPTVActivity.java", "r") as f:
    src = f.read()

# Replace the bare catch in the thread that only shows a Toast — channels stays
# at empty list with no visual feedback. Add: show a retry button / set text.
old = (
    '            } catch (Exception e) {\n'
    '                new Handler(Looper.getMainLooper()).post(() -> {\n'
    '                    loadingBar.setVisibility(View.GONE);\n'
    '                    Toast.makeText(this,\n'
    '                        \"Failed to load channels: \" + e.getMessage(),\n'
    '                        Toast.LENGTH_LONG).show();\n'
    '                });\n'
    '            }\n'
    '        }).start();'
)
new = (
    '            } catch (Exception e) {\n'
    '                android.util.Log.e("IPTVActivity", "loadChannels failed", e);\n'
    '                // Try to fall back to stale cache even if expired\n'
    '                String staleCache = readCachedPlaylist();\n'
    '                if (staleCache == null) {\n'
    '                    // Check for any cache file at all regardless of TTL\n'
    '                    try {\n'
    '                        java.io.File f = new java.io.File(getFilesDir(), CACHE_FILE);\n'
    '                        if (f.exists()) {\n'
    '                            java.io.BufferedReader r2 = new java.io.BufferedReader(new java.io.FileReader(f));\n'
    '                            StringBuilder sb2 = new StringBuilder();\n'
    '                            String line2;\n'
    '                            while ((line2 = r2.readLine()) != null) sb2.append(line2).append("\\n");\n'
    '                            r2.close();\n'
    '                            if (sb2.length() > 0) staleCache = sb2.toString();\n'
    '                        }\n'
    '                    } catch (Exception ignored) {}\n'
    '                }\n'
    '                final String fallback = staleCache;\n'
    '                new Handler(Looper.getMainLooper()).post(() -> {\n'
    '                    loadingBar.setVisibility(View.GONE);\n'
    '                    if (fallback != null) {\n'
    '                        channels = com.neroflix.tv.app.iptv.M3UParser.parse(fallback);\n'
    '                        buildGroupTabs();\n'
    '                        setupRecycler();\n'
    '                        buildEpgTimelineHeader();\n'
    '                        if (!channels.isEmpty()) playChannel(0);\n'
    '                        Toast.makeText(IPTVActivity.this,\n'
    '                            "Offline mode — showing cached channels", Toast.LENGTH_LONG).show();\n'
    '                    } else {\n'
    '                        currentChannelText.setText("Failed to load channels. Check your connection.");\n'
    '                        Toast.makeText(IPTVActivity.this,\n'
    '                            "Failed to load channels: " + e.getMessage(), Toast.LENGTH_LONG).show();\n'
    '                    }\n'
    '                });\n'
    '            }\n'
    '        }).start();'
)

if old in src:
    src = src.replace(old, new, 1)
    print("  IPTVActivity loadChannels: error boundary added with stale-cache fallback")
else:
    print("  Pattern not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/activities/IPTVActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [3/4] Fix #7 & #10: YastreamPlayerActivity — remove debug Toast from showSubtitlePicker ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java", "r") as f:
    src = f.read()

# Remove the debug toast line that spams users
old = '                    android.widget.Toast.makeText(this, "lang=" + fmt.language + " label=" + fmt.label, android.widget.Toast.LENGTH_LONG).show();\n'
new = ''

if old in src:
    src = src.replace(old, new)
    print("  Debug Toast removed from showSubtitlePicker")
else:
    print("  Debug toast pattern not found — may already be removed")

with open("app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [4/4] Fix #9: LicenseManager.fetchYastreamStreams — show error when token is empty ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/LicenseManager.java", "r") as f:
    src = f.read()

# Current code silently returns null when token is empty — user sees blank error
# Replace with a more descriptive log so at least it's traceable
old = (
    '        if (token.isEmpty()) {\n'
    '            callback.onResult(null);\n'
    '            return;\n'
    '        }'
)
new = (
    '        if (token.isEmpty()) {\n'
    '            Log.w("LicenseManager", "fetchYastreamStreams: token is empty — device not activated or token expired");\n'
    '            // Try to re-authenticate before giving up\n'
    '            new Thread(() -> doFullServerCheck(context, deviceId, prefs, servers -> {\n'
    '                // After re-auth, retry if we now have a token\n'
    '                String refreshedToken = prefs.getString(PREF_TOKEN, "");\n'
    '                if (!refreshedToken.isEmpty()) {\n'
    '                    // Re-enter with the new token\n'
    '                    fetchYastreamStreams(context, tmdbId, mediaType, season, episode, callback);\n'
    '                } else {\n'
    '                    Log.e("LicenseManager", "fetchYastreamStreams: re-auth failed, still no token");\n'
    '                    callback.onResult(null);\n'
    '                }\n'
    '            })).start();\n'
    '            return;\n'
    '        }'
)

if old in src:
    src = src.replace(old, new, 1)
    print("  fetchYastreamStreams: empty token now triggers re-auth before giving up")
else:
    print("  Pattern not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/LicenseManager.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "✅ Batch 2 done! Run:"
echo "   git add -A && git commit -m 'Fix batch 2: splash delay, IPTV error boundary, debug toast, token re-auth' && git push"
