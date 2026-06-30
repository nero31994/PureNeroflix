#!/bin/bash
# cleanup_genre_old.sh — Remove the now-unused showGenrePicker() method
# Run from repo root: bash cleanup_genre_old.sh
set -e

python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "r") as f:
    src = f.read()

old_method = '''    private void showGenrePicker() {
        final String[] genres   = {"All","Action","Comedy","Drama","Horror","Sci-Fi","Romance","Animation","Thriller","Crime","Fantasy","Documentary","Family","Mystery"};
        final String[] genreIds = {null,  "28",    "35",    "18",   "27",    "878",   "10749",  "16",       "53",      "80",   "14",     "99",         "10751", "9648"};
        new AlertDialog.Builder(this)
            .setTitle("Filter by Genre")
            .setItems(genres, (d, which) -> {
                String genreId = genreIds[which];
                String[][] defs = genreId == null ? CATEGORY_DEFS : new String[][]{
                    {"🎬 " + genres[which] + " Movies",   "/discover/movie?with_genres=" + genreId, "movie"},
                    {"📺 " + genres[which] + " TV Shows", "/discover/tv?with_genres="    + genreId, "tv"},
                };
                currentMode = "genre_" + which;
                reloadWithDefs(defs);
            }).show();
    }

'''

if old_method in src:
    src = src.replace(old_method, '', 1)
    print("  showGenrePicker() removed — dead code cleaned up")
else:
    print("  Method not found verbatim — checking for partial match...")
    if "private void showGenrePicker()" in src:
        print("  WARNING: method exists but text didn't match exactly.")
        print("  Manual removal may be needed — check around 'showGenrePicker' in the file.")
    else:
        print("  Method already removed or never existed in this form.")

with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== Verify no remaining references ==="
grep -n "showGenrePicker" app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java || echo "  Clean — no references found."

echo ""
echo "✅ Done! Run:"
echo "   git add -A && git commit -m 'Remove dead showGenrePicker() code, replaced by GenreActivity' && git push"
