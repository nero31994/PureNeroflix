#!/bin/bash
# apply_radio_bg.sh — run from repo root: bash apply_radio_bg.sh
set -e
REPO="$(pwd)"
JAVA="app/src/main/java/com/neroflix/tv/app"
RES="app/src/main/res"

echo "=== [1/5] Copy RadioPlayerService ==="
mkdir -p "$REPO/$JAVA/services"
cp RadioPlayerService.java "$REPO/$JAVA/services/RadioPlayerService.java"

echo "=== [2/5] Replace RadioActivity ==="
cp RadioActivity_v2.java "$REPO/$JAVA/activities/RadioActivity.java"

echo "=== [3/5] Replace activity_radio layout ==="
cp activity_radio_v2.xml "$REPO/$RES/layout/activity_radio.xml"

echo "=== [4/5] Add media3-session dependency ==="
GRADLE="$REPO/app/build.gradle"
if ! grep -q "media3-session" "$GRADLE"; then
    sed -i "s|implementation 'androidx.media3:media3-ui:1.3.1'|implementation 'androidx.media3:media3-ui:1.3.1'\n    implementation 'androidx.media3:media3-session:1.3.1'|" "$GRADLE"
    echo "  media3-session added."
else
    echo "  media3-session already present."
fi

echo "=== [5/5] Patch AndroidManifest ==="
python3 - << 'PYEOF'
import re

with open("app/src/main/AndroidManifest.xml", "r") as f:
    content = f.read()

# 1. Add permissions if missing
perms = [
    '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
    '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />',
    '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
]
for perm in perms:
    attr = perm.split('"')[1]
    if attr not in content:
        content = content.replace(
            '<uses-permission android:name="android.permission.INTERNET" />',
            '<uses-permission android:name="android.permission.INTERNET" />\n    ' + perm
        )
        print(f"  Added: {attr}")

# 2. Add RadioPlayerService declaration
service_entry = '''
        <service
            android:name=".services.RadioPlayerService"
            android:exported="true"
            android:foregroundServiceType="mediaPlayback">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
            </intent-filter>
        </service>'''

if "RadioPlayerService" not in content:
    content = content.replace("</application>", service_entry + "\n    </application>")
    print("  RadioPlayerService added to manifest.")
else:
    print("  RadioPlayerService already in manifest.")

with open("app/src/main/AndroidManifest.xml", "w") as f:
    f.write(content)

print("  Manifest done.")
PYEOF

echo ""
echo "✅ Done! Now run:"
echo "   git add -A && git commit -m 'Radio: background audio + notification mini-player' && git push"
