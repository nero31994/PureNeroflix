#!/bin/bash
# fix_title_logo.sh — Replace poster with TMDB PNG title logo on loading screen
# Run from repo root: bash fix_title_logo.sh
set -e

echo "=== [1/3] Add fetchTitleLogo() to TmdbClient ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/network/TmdbClient.java", "r") as f:
    src = f.read()

# Add a new callback interface + method for title logo fetch
old_anchor = '    public interface NetworkCallback {'
new_method = '''    // ── Title logo (PNG treatment, used on loading screen) ──────────────────

    public interface TitleLogoCallback {
        void onSuccess(String logoUrl); // full https URL, transparent PNG
        void onError(String error);
    }

    /**
     * Fetches the TMDB title treatment logo (PNG with transparent background)
     * for a given movie or TV show. This is the stylized title image used by
     * Netflix, Disney+, etc. instead of plain text title overlays.
     *
     * Returns the best English PNG logo URL, or null if none available.
     * Falls back to SVG if no PNG found. Cached permanently (logos rarely change).
     */
    public void fetchTitleLogo(int tmdbId, String mediaType, TitleLogoCallback callback) {
        String endpoint = "/" + ("tv".equals(mediaType) ? "tv" : "movie")
            + "/" + tmdbId + "/images?include_image_language=en,null";
        String url = buildUrl(endpoint);

        // Check persistent logo cache first
        if (context != null) {
            String cached = context
                .getSharedPreferences("tmdb_logo_cache", Context.MODE_PRIVATE)
                .getString("title_logo_" + mediaType + "_" + tmdbId, null);
            if (cached != null) {
                mainHandler.post(() -> callback.onSuccess(cached.isEmpty() ? null : cached));
                return;
            }
        }

        executor.execute(() -> {
            try {
                String body = fetchUrl(url);
                if (body == null || body.isEmpty()) {
                    mainHandler.post(() -> callback.onError("Empty response"));
                    return;
                }

                org.json.JSONObject json = new org.json.JSONObject(body);
                org.json.JSONArray logos = json.optJSONArray("logos");

                String bestLogo = null;
                double bestVote = -1;

                if (logos != null) {
                    // Priority: English PNG > any PNG > English SVG > any SVG
                    String bestPngEn  = null, bestPngAny = null;
                    String bestSvgEn  = null;
                    double bestPngEnV = -1, bestPngAnyV = -1, bestSvgEnV = -1;

                    for (int i = 0; i < logos.length(); i++) {
                        org.json.JSONObject logo = logos.getJSONObject(i);
                        String filePath = logo.optString("file_path", "");
                        String lang     = logo.optString("iso_639_1", "");
                        double vote     = logo.optDouble("vote_average", 0);
                        boolean isPng   = filePath.endsWith(".png");
                        boolean isSvg   = filePath.endsWith(".svg");
                        boolean isEn    = "en".equals(lang) || lang.isEmpty();

                        if (isPng && isEn && vote > bestPngEnV) {
                            bestPngEn = filePath; bestPngEnV = vote;
                        } else if (isPng && vote > bestPngAnyV) {
                            bestPngAny = filePath; bestPngAnyV = vote;
                        } else if (isSvg && isEn && vote > bestSvgEnV) {
                            bestSvgEn = filePath; bestSvgEnV = vote;
                        }
                    }

                    if (bestPngEn  != null) bestLogo = bestPngEn;
                    else if (bestPngAny != null) bestLogo = bestPngAny;
                    else if (bestSvgEn  != null) bestLogo = bestSvgEn;
                }

                String logoUrl = bestLogo != null
                    ? "https://image.tmdb.org/t/p/w500" + bestLogo
                    : null;

                // Cache result (empty string = no logo available, don't retry)
                if (context != null) {
                    context.getSharedPreferences("tmdb_logo_cache", Context.MODE_PRIVATE)
                        .edit().putString("title_logo_" + mediaType + "_" + tmdbId,
                            logoUrl != null ? logoUrl : "").apply();
                }

                final String finalUrl = logoUrl;
                mainHandler.post(() -> {
                    if (finalUrl != null) callback.onSuccess(finalUrl);
                    else callback.onError("No logo available");
                });
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public interface NetworkCallback {'''

if 'fetchTitleLogo' not in src:
    if old_anchor in src:
        src = src.replace(old_anchor, new_method, 1)
        print("  fetchTitleLogo() added to TmdbClient")
    else:
        print("  ERROR: NetworkCallback anchor not found")
else:
    print("  fetchTitleLogo already present — skipping")

with open("app/src/main/java/com/neroflix/tv/app/network/TmdbClient.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [2/3] Update PlayerActivity — fetch title logo, show instead of poster ==="
python3 - << 'PYEOF'
filepath = "app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java"
with open(filepath, "r") as f:
    src = f.read()

# Replace loadLoadingArtwork() with version that fetches title logo
old = '''    private void loadLoadingArtwork() {
        android.widget.ImageView backdrop = findViewById(R.id.player_loading_backdrop);
        android.widget.ImageView poster   = findViewById(R.id.player_loading_poster);
        if (backdrop == null || poster == null) return;
        String tmdbBase = "https://image.tmdb.org/t/p/";
        if (!movieBackdropPath.isEmpty()) {
            com.bumptech.glide.Glide.with(this).load(tmdbBase + "w780" + movieBackdropPath)
                .placeholder(android.R.color.black).into(backdrop);
        } else if (!moviePosterPath.isEmpty()) {
            com.bumptech.glide.Glide.with(this).load(tmdbBase + "w500" + moviePosterPath)
                .placeholder(android.R.color.black).into(backdrop);
        }
        if (!moviePosterPath.isEmpty()) {
            com.bumptech.glide.Glide.with(this).load(tmdbBase + "w342" + moviePosterPath)
                .placeholder(R.drawable.ic_launcher_foreground).into(poster);
        } else {
            poster.setImageResource(R.drawable.ic_launcher_foreground);
        }
    }'''

new = '''    private void loadLoadingArtwork() {
        android.widget.ImageView backdrop = findViewById(R.id.player_loading_backdrop);
        android.widget.ImageView logoView = findViewById(R.id.player_loading_poster);
        if (backdrop == null || logoView == null) return;
        String tmdbBase = "https://image.tmdb.org/t/p/";

        // Load backdrop (dim background)
        if (!movieBackdropPath.isEmpty()) {
            com.bumptech.glide.Glide.with(this)
                .load(tmdbBase + "w780" + movieBackdropPath)
                .placeholder(android.R.color.black).into(backdrop);
        } else if (!moviePosterPath.isEmpty()) {
            com.bumptech.glide.Glide.with(this)
                .load(tmdbBase + "w500" + moviePosterPath)
                .placeholder(android.R.color.black).into(backdrop);
        }

        // Fetch TMDB PNG title logo — transparent background, like Netflix/Disney+
        // Falls back to app icon if no logo available for this title.
        if (movieId > 0) {
            com.neroflix.tv.app.network.TmdbClient.getInstance(this)
                .fetchTitleLogo(movieId, mediaType,
                    new com.neroflix.tv.app.network.TmdbClient.TitleLogoCallback() {
                        @Override
                        public void onSuccess(String logoUrl) {
                            if (isFinishing() || isDestroyed()) return;
                            com.bumptech.glide.Glide.with(PlayerActivity.this)
                                .load(logoUrl)
                                .placeholder(R.drawable.ic_launcher_foreground)
                                .error(R.drawable.ic_launcher_foreground)
                                .into(logoView);
                        }
                        @Override
                        public void onError(String error) {
                            // No logo — keep showing app icon (already default)
                        }
                    });
        } else {
            logoView.setImageResource(R.drawable.ic_launcher_foreground);
        }
    }'''

if old in src:
    src = src.replace(old, new, 1)
    print("  loadLoadingArtwork(): now fetches TMDB title logo")
else:
    print("  ERROR: loadLoadingArtwork pattern not found — check manually")

with open(filepath, "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [3/3] Update layout — make logo ImageView wider + no fixed height ==="
python3 - << 'PYEOF'
with open("app/src/main/res/layout/activity_player.xml", "r") as f:
    src = f.read()

# Make the logo ImageView wider and shorter — title logos are wide, not tall
old = '''            <ImageView
                android:id="@+id/player_loading_poster"
                android:layout_width="120dp"
                android:layout_height="120dp"
                android:scaleType="fitCenter"
                android:layout_marginBottom="24dp" />'''

new = '''            <ImageView
                android:id="@+id/player_loading_poster"
                android:layout_width="280dp"
                android:layout_height="100dp"
                android:scaleType="fitCenter"
                android:adjustViewBounds="true"
                android:layout_marginBottom="28dp" />'''

if old in src:
    src = src.replace(old, new, 1)
    print("  Logo ImageView: resized to wide format for title logos")
else:
    print("  ERROR: ImageView pattern not found")

with open("app/src/main/res/layout/activity_player.xml", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "✅ Done! Run:"
echo "   git add -A && git commit -m 'Loading screen: TMDB PNG title logo instead of poster' && git push"
