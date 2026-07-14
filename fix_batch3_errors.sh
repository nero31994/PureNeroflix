#!/bin/bash
cd ~/PureNeroflix 2>/dev/null || { echo "Run from repo root"; exit 1; }

echo "=== Fix 1 & 2: YastreamPlayerActivity — LANG_NAMES + ln references ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java", "r") as f:
    src = f.read()

# Check current state
has_lang_names = "private static final java.util.Map<String, String> LANG_NAMES" in src
has_ln_ref = "ln.containsKey" in src
print(f"  LANG_NAMES present: {has_lang_names}")
print(f"  ln references present: {has_ln_ref}")

# 1. Ensure LANG_NAMES static field exists — add before class fields if missing
if not has_lang_names:
    # Insert after the class declaration line
    insert_after = "public class YastreamPlayerActivity extends AppCompatActivity {"
    lang_names_field = '''

    // Language code → display name
    private static final java.util.Map<String, String> LANG_NAMES;
    static {
        LANG_NAMES = new java.util.HashMap<>();
        LANG_NAMES.put("eng", "English");   LANG_NAMES.put("tgl", "Filipino");
        LANG_NAMES.put("msa", "Malay");     LANG_NAMES.put("ind", "Indonesian");
        LANG_NAMES.put("tha", "Thai");      LANG_NAMES.put("khm", "Khmer");
        LANG_NAMES.put("ara", "Arabic");    LANG_NAMES.put("deu", "German");
        LANG_NAMES.put("fra", "French");    LANG_NAMES.put("spa", "Spanish");
        LANG_NAMES.put("zho", "Chinese");   LANG_NAMES.put("jpn", "Japanese");
        LANG_NAMES.put("kor", "Korean");    LANG_NAMES.put("por", "Portuguese");
        LANG_NAMES.put("ita", "Italian");   LANG_NAMES.put("rus", "Russian");
        LANG_NAMES.put("vie", "Vietnamese"); LANG_NAMES.put("und", "Unknown");
    }'''
    if insert_after in src:
        src = src.replace(insert_after, insert_after + lang_names_field, 1)
        print("  LANG_NAMES static field inserted")
    else:
        print("  ERROR: class declaration not found")

# 2. Fix any remaining ln. references in showSubtitlePicker
src = src.replace(
    "} else if (ln.containsKey(lang)) {",
    "} else if (LANG_NAMES.containsKey(lang)) {"
)
src = src.replace(
    "label = ln.get(lang);",
    "label = LANG_NAMES.get(lang);"
)
# Also remove any leftover inline ln map declarations in showSubtitlePicker
import re
# Remove the inline HashMap block for ln if it still exists
src = re.sub(
    r'[ \t]+java\.util\.Map<String,String> ln = new java\.util\.HashMap<>\(\);[^\n]*\n'
    r'(?:[ \t]+ln\.put\([^;]+;\n)+',
    '',
    src
)
print(f"  ln references fixed: {'ln.containsKey' not in src}")

with open("app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java", "w") as f:
    f.write(src)
print("  YastreamPlayerActivity saved.")
PYEOF

echo ""
echo "=== Fix 3: MainActivity — replace missing placeholder_backdrop drawable ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "r") as f:
    src = f.read()

# Replace the non-existent drawable with a safe fallback (android color)
old = ".placeholder(R.drawable.placeholder_backdrop)"
new = ".placeholder(android.R.color.black)"

if old in src:
    src = src.replace(old, new)
    print("  placeholder_backdrop → android.R.color.black")
else:
    print("  placeholder already fixed or not found")

with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "w") as f:
    f.write(src)
print("  MainActivity saved.")
PYEOF

echo ""
echo "✅ Done! Run:"
echo "   git add -A && git commit -m 'Fix build errors: LANG_NAMES, ln ref, placeholder_backdrop' && git push"
