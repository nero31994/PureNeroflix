#!/bin/bash
# fix_batch3.sh — Minor fixes (issues 11, 12, 13)
# Run from repo root: bash fix_batch3.sh
set -e

echo "=== [1/3] Fix #12: WatchlistActivity — set initial D-pad focus on RecyclerView ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/WatchlistActivity.java", "r") as f:
    src = f.read()

# After adapter is set, request focus on the recycler so D-pad works immediately
old = (
    '        recycler.setAdapter(adapter);\n'
    '    }\n'
    '\n'
    '    @Override\n'
    '    public boolean onKeyDown(int keyCode, KeyEvent event) {'
)
new = (
    '        recycler.setAdapter(adapter);\n'
    '\n'
    '        // Set initial D-pad focus so remote works immediately on entering the screen\n'
    '        if (!movies.isEmpty()) {\n'
    '            recycler.setFocusable(true);\n'
    '            recycler.setFocusableInTouchMode(false);\n'
    '            recycler.post(() -> recycler.requestFocus());\n'
    '        }\n'
    '    }\n'
    '\n'
    '    @Override\n'
    '    public boolean onKeyDown(int keyCode, KeyEvent event) {'
)

if old in src:
    src = src.replace(old, new, 1)
    print("  WatchlistActivity: initial D-pad focus added")
else:
    print("  Pattern not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/activities/WatchlistActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [2/3] Fix #11: MainActivity — implement updateHero() to actually show hero section ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "r") as f:
    src = f.read()

# 2a: Replace the stub updateHero() with a real implementation
old = '    private void updateHero(Movie movie) { /* hero hidden */ }'
new = '''    private void updateHero(Movie movie) {
        if (movie == null || heroSection == null) return;
        heroSection.setVisibility(View.VISIBLE);
        if (heroTitle    != null) heroTitle.setText(movie.getTitle());
        if (heroOverview != null) heroOverview.setText(movie.getOverview());
        if (heroRating   != null) heroRating.setText(String.format("★ %.1f", movie.getVoteAverage()));
        if (heroYear     != null) heroYear.setText(movie.getYear());
        if (heroBackdrop != null && movie.getBackdropPath() != null && !movie.getBackdropPath().isEmpty()) {
            com.bumptech.glide.Glide.with(this)
                .load("https://image.tmdb.org/t/p/w1280" + movie.getBackdropPath())
                .placeholder(R.drawable.placeholder_backdrop)
                .into(heroBackdrop);
        }
    }'''

if old in src:
    src = src.replace(old, new, 1)
    print("  updateHero(): stub replaced with real implementation")
else:
    print("  updateHero stub not found — check manually")

# 2b: In loadContent(), call updateHero() after setting heroMovie
old = (
    '            @Override public void onSuccess(List<Movie> movies) {\n'
    '                if (!movies.isEmpty()) { heroMovie = movies.get(0); }\n'
    '            }\n'
    '            @Override public void onError(String e) {}\n'
    '        });'
)
new = (
    '            @Override public void onSuccess(List<Movie> movies) {\n'
    '                if (!movies.isEmpty()) {\n'
    '                    heroMovie = movies.get(0);\n'
    '                    runOnUiThread(() -> updateHero(heroMovie));\n'
    '                }\n'
    '            }\n'
    '            @Override public void onError(String e) {}\n'
    '        });'
)

if old in src:
    src = src.replace(old, new, 1)
    print("  loadContent(): updateHero() now called after trending fetch")
else:
    print("  loadContent pattern not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [3/3] Fix #13: YastreamPlayerActivity — extract langNames map out of loop ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java", "r") as f:
    src = f.read()

# The langNames map is built fresh on every iteration inside two separate loops.
# Replace both inline declarations with a reference to a single static final map.

# First, add a static field just before the showSubtitlePicker method
static_map = '''    // Language code → display name (shared by subtitle picker and player init)
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
    }

'''

insert_before = '    // ── Subtitle picker ───────────────────────────────────────────────────────────────────'
if insert_before in src:
    src = src.replace(insert_before, static_map + insert_before, 1)
    print("  Static LANG_NAMES map added")
else:
    print("  Subtitle picker marker not found — check manually")

# Remove both inline langNames map declarations in showSubtitlePicker
inline_map_picker = (
    '                    java.util.Map<String,String> ln = new java.util.HashMap<>();\n'
    '                    ln.put("eng","English"); ln.put("tgl","Filipino");\n'
    '                    ln.put("msa","Malay"); ln.put("ind","Indonesian");\n'
    '                    ln.put("tha","Thai"); ln.put("khm","Khmer");\n'
    '                    ln.put("ara","Arabic"); ln.put("deu","German");\n'
    '                    ln.put("fra","French"); ln.put("spa","Spanish");\n'
    '                    ln.put("zho","Chinese"); ln.put("jpn","Japanese");\n'
    '                    ln.put("kor","Korean"); ln.put("por","Portuguese");\n'
    '                    ln.put("ita","Italian"); ln.put("rus","Russian");\n'
    '                    ln.put("vie","Vietnamese");\n'
)
if inline_map_picker in src:
    src = src.replace(inline_map_picker, '', 1)
    # Update references from ln. to LANG_NAMES.
    src = src.replace(
        '                    if (ln.containsKey(lang)) {\n'
        '                        label = ln.get(lang);\n',
        '                    if (LANG_NAMES.containsKey(lang)) {\n'
        '                        label = LANG_NAMES.get(lang);\n'
    )
    print("  Inline langNames map removed from showSubtitlePicker")
else:
    print("  Inline picker map not found — may already be clean")

# Remove inline map in initExoPlayer
inline_map_player = (
    '                        java.util.Map<String,String> langNames = new java.util.HashMap<>();\n'
    '                        langNames.put("eng","English"); langNames.put("tgl","Filipino");\n'
    '                        langNames.put("msa","Malay"); langNames.put("ind","Indonesian");\n'
    '                        langNames.put("tha","Thai"); langNames.put("khm","Khmer");\n'
    '                        langNames.put("ara","Arabic"); langNames.put("deu","German");\n'
    '                        langNames.put("fra","French"); langNames.put("spa","Spanish");\n'
    '                        langNames.put("zho","Chinese"); langNames.put("jpn","Japanese");\n'
    '                        langNames.put("kor","Korean"); langNames.put("por","Portuguese");\n'
    '                        langNames.put("ita","Italian"); langNames.put("rus","Russian");\n'
    '                        langNames.put("vie","Vietnamese"); langNames.put("und","Unknown");\n'
)
if inline_map_player in src:
    src = src.replace(inline_map_player, '', 1)
    # Update reference in initExoPlayer
    src = src.replace(
        '                        String subLabel = langNames.containsKey(subLang)\n'
        '                            ? langNames.get(subLang) : subLang.toUpperCase();',
        '                        String subLabel = LANG_NAMES.containsKey(subLang)\n'
        '                            ? LANG_NAMES.get(subLang) : subLang.toUpperCase();'
    )
    print("  Inline langNames map removed from initExoPlayer")
else:
    print("  Inline player map not found — may already be clean")

with open("app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "✅ Batch 3 done! Run:"
echo "   git add -A && git commit -m 'Fix batch 3: hero section, watchlist focus, langNames dedup' && git push"
