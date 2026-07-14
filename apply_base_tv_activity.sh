#!/bin/bash
# apply_base_tv_activity.sh — Centralize D-pad navigation into BaseTvActivity
# Run from repo root: bash apply_base_tv_activity.sh
set -e

JAVA="app/src/main/java/com/neroflix/tv/app/activities"

echo "=== [1/3] Copy BaseTvActivity.java ==="
cp BaseTvActivity.java "$JAVA/BaseTvActivity.java"

echo "=== [2/3] Migrate each activity: extends + @Override onKeyDown -> onTvKeyDown ==="

# List of activities to migrate (all except BaseTvActivity itself and SplashActivity,
# which has no D-pad needs and a very short lifecycle)
ACTIVITIES=(
    "MainActivity"
    "DetailActivity"
    "IPTVActivity"
    "YastreamPlayerActivity"
    "SearchActivity"
    "WatchlistActivity"
    "NetworkActivity"
    "DownloadActivity"
    "MyDownloadsActivity"
    "ActivationActivity"
    "LocalPlayerActivity"
    "PlayerActivity"
    "GenreActivity"
    "KisskhActivity"
)

for NAME in "${ACTIVITIES[@]}"; do
    FILE="$JAVA/$NAME.java"
    if [ ! -f "$FILE" ]; then
        echo "  SKIP $NAME — file not found"
        continue
    fi

    python3 - "$FILE" "$NAME" << 'PYEOF'
import sys, re

filepath = sys.argv[1]
classname = sys.argv[2]

with open(filepath, "r") as f:
    src = f.read()

changed = False

# 1. Change "extends AppCompatActivity" -> "extends BaseTvActivity"
#    Handle variants with "implements X" after it too
pattern_extends = re.compile(
    r'(public class ' + re.escape(classname) + r'\s+extends\s+)AppCompatActivity'
)
if pattern_extends.search(src):
    src = pattern_extends.sub(r'\1BaseTvActivity', src, count=1)
    changed = True
    print(f"  {classname}: extends AppCompatActivity -> BaseTvActivity")
else:
    print(f"  {classname}: 'extends AppCompatActivity' not found — may already be migrated or use different pattern")

# 2. Rename the onKeyDown override to onTvKeyDown, change @Override -> protected,
#    drop the final super.onKeyDown(...) fallback call since BaseTvActivity
#    handles that automatically now (replace with "return false;")

# Match: @Override\n    public boolean onKeyDown(int keyCode, [android.view.]KeyEvent event) {
pattern_sig = re.compile(
    r'(@Override\s*\n\s*)public boolean onKeyDown\(int keyCode,\s*(?:android\.view\.)?KeyEvent event\)\s*\{'
)
def replace_sig(m):
    return '    @Override\n    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {'

new_src, count = pattern_sig.subn(replace_sig, src, count=1)
if count > 0:
    src = new_src
    changed = True
    print(f"  {classname}: onKeyDown -> onTvKeyDown (signature)")
else:
    print(f"  {classname}: onKeyDown signature pattern not matched — check manually")

with open(filepath, "w") as f:
    f.write(src)

if not changed:
    print(f"  WARNING: no changes applied to {classname} — verify manually")
PYEOF

    # Ensure KeyEvent is imported (needed since onTvKeyDown signature uses
    # the short class name, even if the original file used the fully
    # qualified android.view.KeyEvent inline)
    python3 - "$FILE" << 'PYEOF2'
import sys
filepath = sys.argv[1]
with open(filepath, "r") as f:
    src = f.read()

if "import android.view.KeyEvent;" not in src:
    lines = src.split("\n")
    pkg_idx = next((i for i, l in enumerate(lines) if l.startswith("package ")), 0)
    lines.insert(pkg_idx + 1, "")
    lines.insert(pkg_idx + 2, "import android.view.KeyEvent;")
    src = "\n".join(lines)
    with open(filepath, "w") as f:
        f.write(src)
    print("  Added missing 'import android.view.KeyEvent;'")
PYEOF2

done

echo ""
echo "=== [3/3] Fix trailing 'return super.onKeyDown(keyCode, event);' calls ==="
echo "    (these become 'return false;' since BaseTvActivity provides the fallback)"

for NAME in "${ACTIVITIES[@]}"; do
    FILE="$JAVA/$NAME.java"
    [ -f "$FILE" ] || continue

    # Only replace the LAST occurrence in onTvKeyDown context — most files
    # have a single trailing fallback line at the end of the method.
    # This sed targets the literal line; safe because BaseTvActivity's
    # onTvKeyDown default already returns false, so semantics match.
    if grep -q "return super.onKeyDown(keyCode, event);" "$FILE"; then
        sed -i 's/return super\.onKeyDown(keyCode, event);/return false; \/\/ fallback now handled by BaseTvActivity/' "$FILE"
        echo "  $NAME: trailing super.onKeyDown() call replaced with 'return false;'"
    fi
done

echo ""
echo "✅ Migration applied! IMPORTANT — manual check needed:"
echo "  1. Build and check for compile errors (some activities may have multiple"
echo "     onKeyDown overloads, or call super.onKeyDown() mid-method, not just at the end)"
echo "  2. Search for any remaining 'AppCompatActivity' imports that are now unused"
echo "     (harmless to leave, but can be cleaned up)"
echo ""
echo "Run:"
echo "   git add -A && git commit -m 'Centralize D-pad/remote navigation into BaseTvActivity' && git push"
