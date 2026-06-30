#!/bin/bash
# apply_genre_grid.sh — Add GenreActivity grid screen + wire up nav
# Run from repo root: bash apply_genre_grid.sh
set -e

JAVA="app/src/main/java/com/neroflix/tv/app"
RES="app/src/main/res"

echo "=== [1/5] Copy GenreActivity.java ==="
cp GenreActivity.java "$JAVA/activities/GenreActivity.java"

echo "=== [2/5] Copy layout ==="
cp activity_genre.xml "$RES/layout/activity_genre.xml"

echo "=== [3/5] Copy drawables ==="
cp genre_chip_active_bg.xml    "$RES/drawable/genre_chip_active_bg.xml"
cp genre_chip_inactive_bg.xml  "$RES/drawable/genre_chip_inactive_bg.xml"
cp genre_toggle_track_bg.xml   "$RES/drawable/genre_toggle_track_bg.xml"
cp genre_toggle_active_bg.xml  "$RES/drawable/genre_toggle_active_bg.xml"

echo "=== [4/5] Patch MainActivity — make 'Genre' nav item open GenreActivity ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "r") as f:
    src = f.read()

# Replace the showGenrePicker() call in the nav switch with launching GenreActivity
old = "case 9: showGenrePicker(); break;"
new = "case 9: startActivity(new Intent(this, GenreActivity.class)); break;"

if old in src:
    src = src.replace(old, new, 1)
    print("  Nav case 9: now launches GenreActivity")
else:
    print("  Pattern not found — check manually")

# Ensure import exists (GenreActivity is in same package, no import needed actually
# since it's in .activities package already — MainActivity is too)

with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "w") as f:
    f.write(src)
PYEOF

echo "=== [5/5] Patch AndroidManifest.xml — declare GenreActivity ==="
python3 - << 'PYEOF'
with open("app/src/main/AndroidManifest.xml", "r") as f:
    content = f.read()

entry = '''
        <activity android:name=".activities.GenreActivity"
            android:exported="false"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:screenOrientation="landscape" />'''

if "GenreActivity" not in content:
    content = content.replace("</application>", entry + "\n    </application>")
    print("  GenreActivity added to manifest.")
else:
    print("  GenreActivity already in manifest.")

with open("app/src/main/AndroidManifest.xml", "w") as f:
    f.write(content)
PYEOF

echo ""
echo "✅ Done! Run:"
echo "   git add -A && git commit -m 'Add GenreActivity: grid view with genre tabs + infinite scroll' && git push"
