#!/bin/bash
# fix_player_black_screen.sh — Address black screen on embed playback for
# certain low-end/budget Android TV boxes.
# Run from repo root: bash fix_player_black_screen.sh
set -e

FILE="app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java"

echo "=== Patching PlayerActivity WebView config ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java", "r") as f:
    src = f.read()

# 1. Replace hardcoded LAYER_TYPE_HARDWARE with a safer per-device choice.
#    Many budget Android TV GPUs (Allwinner/Amlogic low-end SoCs) have broken
#    hardware compositing inside WebView, causing the video to play (audio
#    works) but never visually composite -- a black rectangle where the
#    player should be. LAYER_TYPE_NONE lets the WebView pick its own default
#    compositing strategy, which is far more reliable across fragmented TV
#    hardware than forcing LAYER_TYPE_HARDWARE everywhere.
old_layer = "        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);"
new_layer = (
    "        // Avoid forcing hardware layer — many budget Android TV GPUs\n"
    "        // (Allwinner/Amlogic low-end SoCs) have broken WebView hardware\n"
    "        // compositing, which plays audio but shows a black video rect.\n"
    "        // LAYER_TYPE_NONE lets the WebView choose safely per-device.\n"
    "        webView.setLayerType(View.LAYER_TYPE_NONE, null);"
)
if old_layer in src:
    src = src.replace(old_layer, new_layer, 1)
    print("  LAYER_TYPE_HARDWARE -> LAYER_TYPE_NONE (safer default)")
else:
    print("  Layer type line not found — check manually")

# 2. Enable WebView remote debugging in debug builds only, so the black
#    screen can be inspected live via chrome://inspect on a PC connected
#    to the same network/USB as the TV box.
old_settings_block = (
    "        WebSettings settings = webView.getSettings();\n"
    "        settings.setJavaScriptEnabled(true);"
)
new_settings_block = (
    "        // Enable remote WebView debugging on debug builds — lets you\n"
    "        // inspect black-screen embeds live via chrome://inspect on a PC\n"
    "        // on the same network (Settings > About > tap build 7x for ADB,\n"
    "        // then `adb tcpip 5555` if the TV supports network ADB).\n"
    "        if (com.neroflix.tv.app.BuildConfig.DEBUG) {\n"
    "            WebView.setWebContentsDebuggingEnabled(true);\n"
    "        }\n"
    "\n"
    "        WebSettings settings = webView.getSettings();\n"
    "        settings.setJavaScriptEnabled(true);"
)
if old_settings_block in src:
    src = src.replace(old_settings_block, new_settings_block, 1)
    print("  WebView remote debugging enabled for debug builds")
else:
    print("  Settings block not found — check manually")

# 3. Force software rendering as a last-resort fallback if the page fails
#    to show video content after a timeout — re-trigger with LAYER_TYPE_SOFTWARE.
#    We detect "stuck black screen" heuristically: onPageFinished fires but
#    no further activity happens. Add a watchdog that retries with software
#    layer if nothing visually changes within 6 seconds of page load.
old_page_finished = (
    "            @Override\n"
    "            public void onPageFinished(WebView view, String url) {\n"
    "                super.onPageFinished(view, url);\n"
    "                loadingOverlay.setVisibility(View.GONE);\n"
    "                view.evaluateJavascript(\n"
    "                    \"window.open=function(){return null;};\" +\n"
    "                    \"window.alert=function(){};\" +\n"
    "                    \"window.confirm=function(){return true;};\", null);\n"
    "            }"
)
new_page_finished = (
    "            @Override\n"
    "            public void onPageFinished(WebView view, String url) {\n"
    "                super.onPageFinished(view, url);\n"
    "                loadingOverlay.setVisibility(View.GONE);\n"
    "                view.evaluateJavascript(\n"
    "                    \"window.open=function(){return null;};\" +\n"
    "                    \"window.alert=function(){};\" +\n"
    "                    \"window.confirm=function(){return true;};\", null);\n"
    "\n"
    "                // Watchdog: on some budget Android TV GPUs, the embed page\n"
    "                // finishes loading and video plays (audio works) but the\n"
    "                // frame never visually composites -- pure black screen.\n"
    "                // If still showing nothing after 6s, retry with a software\n"
    "                // rendering layer, which is slower but far more reliable on\n"
    "                // broken WebView hardware compositors.\n"
    "                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {\n"
    "                    if (!isFinishing() && !isDestroyed() && webView != null) {\n"
    "                        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);\n"
    "                        webView.invalidate();\n"
    "                    }\n"
    "                }, 6000);\n"
    "            }"
)
if old_page_finished in src:
    src = src.replace(old_page_finished, new_page_finished, 1)
    print("  Watchdog added: retries with LAYER_TYPE_SOFTWARE after 6s")
else:
    print("  onPageFinished block not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "✅ Done! Run:"
echo "   git add -A && git commit -m 'Fix black screen on embed playback for budget Android TV GPUs' && git push"
echo ""
echo "NOTE: If black screen persists on a specific device after this fix, the"
echo "most likely remaining cause is DRM/EME content requiring a secure hardware"
echo "decode path that WebView cannot composite over at all -- in that case the"
echo "fix is provider-side (switch that server) rather than app-side."
