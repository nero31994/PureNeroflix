#!/bin/bash
# apply_radio.sh — run from repo root: bash apply_radio.sh
# Replaces K-Drama nav item with Radio in PureNeroflix

set -e
REPO_ROOT="$(pwd)"
JAVA_PKG="app/src/main/java/com/neroflix/tv/app"
RES="app/src/main/res"

echo "=== [1/5] Copying RadioActivity.java ==="
cp RadioActivity.java "$REPO_ROOT/$JAVA_PKG/activities/RadioActivity.java"

echo "=== [2/5] Copying layouts ==="
cp activity_radio.xml   "$REPO_ROOT/$RES/layout/activity_radio.xml"
cp item_radio_channel.xml "$REPO_ROOT/$RES/layout/item_radio_channel.xml"

echo "=== [3/5] Copying icon ==="
cp ic_radio.xml "$REPO_ROOT/$RES/drawable/ic_radio.xml"

echo "=== [4/5] Patching MainActivity.java ==="
MAIN="$REPO_ROOT/$JAVA_PKG/activities/MainActivity.java"

# 4a: Replace nav label "K-Drama" → "Radio"
sed -i 's/"K-Drama"/"Radio"/' "$MAIN"

# 4b: Replace KisskhActivity launch → RadioActivity launch
sed -i 's/com\.neroflix\.tv\.app\.activities\.KisskhActivity\.class/com.neroflix.tv.app.activities.RadioActivity.class/' "$MAIN"

echo "=== [5/5] Patching AndroidManifest.xml ==="
MANIFEST="$REPO_ROOT/app/src/main/AndroidManifest.xml"

# Add RadioActivity declaration right after KisskhActivity block
# Using Python for reliable multi-line insert
python3 - <<'PYEOF'
import re, sys

with open("app/src/main/AndroidManifest.xml", "r") as f:
    content = f.read()

radio_entry = '''
        <activity android:name=".activities.RadioActivity"
            android:exported="false"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:screenOrientation="landscape" />'''

# Only add if not already present
if "RadioActivity" not in content:
    # Insert after KisskhActivity closing tag
    content = re.sub(
        r'(android:name="\.activities\.KisskhActivity"[^/]*/\s*>)',
        r'\1' + radio_entry,
        content,
        count=1
    )
    with open("app/src/main/AndroidManifest.xml", "w") as f:
        f.write(content)
    print("  Manifest patched.")
else:
    print("  RadioActivity already in manifest — skipped.")
PYEOF

echo ""
echo "✅ Done! Now run:"
echo "   git add -A && git commit -m 'Replace K-Drama with Radio (M3U player)' && git push"
echo ""
echo "⚠  Remember to set your M3U URL inside RadioActivity.java"
echo "   RADIO_M3U_URL constant at the top of the file."
