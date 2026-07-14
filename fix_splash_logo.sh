#!/bin/bash
# fix_splash_logo.sh — Replace splash logo + gray dark gradient background
# Run from repo root: bash fix_splash_logo.sh
set -e

RES="app/src/main/res"

echo "=== [1/3] Verify splash_logo.png exists ==="
if [ ! -f "$RES/drawable/splash_logo.png" ]; then
    echo "  ERROR: $RES/drawable/splash_logo.png not found!"
    echo "  Run: cp /storage/emulated/0/Pictures/PhotoLayers/1782764165430.png $RES/drawable/splash_logo.png"
    exit 1
fi
echo "  Found splash_logo.png"

echo ""
echo "=== [2/3] Replace splash_radial_bg.xml with gray dark gradient ==="
cat > "$RES/drawable/splash_radial_bg.xml" << 'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient
        android:type="linear"
        android:angle="135"
        android:startColor="#3A3A3A"
        android:centerColor="#1C1C1C"
        android:endColor="#0A0A0A"
        android:centerY="0.5"/>
</shape>
XMLEOF
echo "  splash_radial_bg.xml replaced with gray → dark linear gradient"

echo ""
echo "=== [3/3] Update activity_splash.xml — swap logo source, base background ==="
python3 - << 'PYEOF'
with open("app/src/main/res/layout/activity_splash.xml", "r") as f:
    src = f.read()

# 1. Base RelativeLayout background — change from solid #111111 to dark gray
src = src.replace(
    'android:background="#111111">',
    'android:background="#1A1A1A">'
)

# 2. Swap logo drawable reference from splash_nero_logo to splash_logo
src = src.replace(
    'android:src="@drawable/splash_nero_logo"',
    'android:src="@drawable/splash_logo"'
)

with open("app/src/main/res/layout/activity_splash.xml", "w") as f:
    f.write(src)

print("  activity_splash.xml updated: background + logo source")
PYEOF

echo ""
echo "✅ Done! Run:"
echo "   git add -A && git commit -m 'Update splash: new logo + gray dark gradient background' && git push"
