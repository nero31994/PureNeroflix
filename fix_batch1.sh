#!/bin/bash
# fix_batch1.sh — Critical crash fixes (issues 1-4)
# Run from repo root: bash fix_batch1.sh
set -e

TMDB="app/src/main/java/com/neroflix/tv/app/network/TmdbClient.java"
DETAIL="app/src/main/java/com/neroflix/tv/app/activities/DetailActivity.java"

echo "=== [1/3] Fix #1 & #2: response.body() NPE in TmdbClient ==="

python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/network/TmdbClient.java", "r") as f:
    src = f.read()

# Fix 1a: fetchLogoById — response.body().string() with no null check
old = (
    '                Request request = new Request.Builder().url(url).build();\n'
    '                Response response = httpClient.newCall(request).execute();\n'
    '                String body = response.body().string();\n'
    '                jsonCache.put(url, body);\n'
    '                JSONObject json = new JSONObject(body);\n'
    '                String logoPath = json.optString("logo_path", "");\n'
    '                mainHandler.post(() -> callback.onSuccess(logoPath));'
)
new = (
    '                Request request = new Request.Builder().url(url).build();\n'
    '                Response response = httpClient.newCall(request).execute();\n'
    '                okhttp3.ResponseBody rb = response.body();\n'
    '                if (rb == null) { mainHandler.post(() -> callback.onError("Empty response")); return; }\n'
    '                String body = rb.string();\n'
    '                jsonCache.put(url, body);\n'
    '                JSONObject json = new JSONObject(body);\n'
    '                String logoPath = json.optString("logo_path", "");\n'
    '                mainHandler.post(() -> callback.onSuccess(logoPath));'
)
if old in src:
    src = src.replace(old, new, 1)
    print("  fetchLogoById: fixed")
else:
    print("  fetchLogoById: pattern not found - check manually")

# Fix 1b: fetchTVDetails — response.body().string() with no null check
old = (
    '                String url = BASE_URL + "/tv/" + tvId + "?api_key=" + API_KEY;\n'
    '                Request request = new Request.Builder().url(url).build();\n'
    '                Response response = httpClient.newCall(request).execute();\n'
    '                JSONObject json = new JSONObject(response.body().string());\n'
    '                JSONArray seasonsArray'
)
new = (
    '                String url = BASE_URL + "/tv/" + tvId + "?api_key=" + API_KEY;\n'
    '                Request request = new Request.Builder().url(url).build();\n'
    '                Response response = httpClient.newCall(request).execute();\n'
    '                okhttp3.ResponseBody tvBody = response.body();\n'
    '                if (tvBody == null) { mainHandler.post(() -> callback.onError("Empty response")); return; }\n'
    '                JSONObject json = new JSONObject(tvBody.string());\n'
    '                JSONArray seasonsArray'
)
if old in src:
    src = src.replace(old, new, 1)
    print("  fetchTVDetails: fixed")
else:
    print("  fetchTVDetails: pattern not found - check manually")

# Fix 1c: fetchEpisodes — response.body().string() with no null check
old = (
    '                String url = BASE_URL + "/tv/" + tvId + "/season/" + season + "?api_key=" + API_KEY;\n'
    '                Request request = new Request.Builder().url(url).build();\n'
    '                Response response = httpClient.newCall(request).execute();\n'
    '                JSONObject json = new JSONObject(response.body().string());\n'
    '                JSONArray eps'
)
new = (
    '                String url = BASE_URL + "/tv/" + tvId + "/season/" + season + "?api_key=" + API_KEY;\n'
    '                Request request = new Request.Builder().url(url).build();\n'
    '                Response response = httpClient.newCall(request).execute();\n'
    '                okhttp3.ResponseBody epBody = response.body();\n'
    '                if (epBody == null) { mainHandler.post(() -> callback.onError("Empty response")); return; }\n'
    '                JSONObject json = new JSONObject(epBody.string());\n'
    '                JSONArray eps'
)
if old in src:
    src = src.replace(old, new, 1)
    print("  fetchEpisodes: fixed")
else:
    print("  fetchEpisodes: pattern not found - check manually")

with open("app/src/main/java/com/neroflix/tv/app/network/TmdbClient.java", "w") as f:
    f.write(src)

print("  TmdbClient.java saved.")
PYEOF

echo ""
echo "=== [2/3] Fix #3: DetailActivity.loadDetailInfo getInstance() without context ==="

python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/DetailActivity.java", "r") as f:
    src = f.read()

# The call is TmdbClient.getInstance() without context — replace with getInstance(this)
old = 'TmdbClient.getInstance().fetchMovieDetail(movieId, mediaType, new TmdbClient.MovieDetailCallback() {'
new = 'TmdbClient.getInstance(this).fetchMovieDetail(movieId, mediaType, new TmdbClient.MovieDetailCallback() {'

if old in src:
    src = src.replace(old, new, 1)
    print("  loadDetailInfo: fixed")
else:
    print("  loadDetailInfo: pattern not found - may already be fixed")

# Also fix the other getInstance() calls without context in DetailActivity
fixes = [
    (
        'com.neroflix.tv.app.network.TmdbClient.getInstance().fetchTVDetails(movieId,',
        'com.neroflix.tv.app.network.TmdbClient.getInstance(this).fetchTVDetails(movieId,'
    ),
    (
        'com.neroflix.tv.app.network.TmdbClient.getInstance().fetchEpisodes(movieId, season,',
        'com.neroflix.tv.app.network.TmdbClient.getInstance(this).fetchEpisodes(movieId, season,'
    ),
    (
        'com.neroflix.tv.app.network.TmdbClient.getInstance().fetchTVDetails(movieId,\n            new com.neroflix.tv.app.network.TmdbClient.TVDetailsCallback() {\n                @Override\n                public void onSuccess(int numSeasons, java.util.List<String> seasonNames) {\n                    loadingDialog.dismiss();\n                    String[] seasons = seasonNames.toArray(new String[0]);\n                    new AlertDialog.Builder(DetailActivity.this)\n                        .setTitle("Select Season to Download")',
        'com.neroflix.tv.app.network.TmdbClient.getInstance(this).fetchTVDetails(movieId,\n            new com.neroflix.tv.app.network.TmdbClient.TVDetailsCallback() {\n                @Override\n                public void onSuccess(int numSeasons, java.util.List<String> seasonNames) {\n                    loadingDialog.dismiss();\n                    String[] seasons = seasonNames.toArray(new String[0]);\n                    new AlertDialog.Builder(DetailActivity.this)\n                        .setTitle("Select Season to Download")'
    ),
]
for old, new in fixes:
    if old in src:
        src = src.replace(old, new)
        print(f"  Fixed additional getInstance() call")

with open("app/src/main/java/com/neroflix/tv/app/activities/DetailActivity.java", "w") as f:
    f.write(src)
print("  DetailActivity.java saved.")
PYEOF

echo ""
echo "=== [3/3] Fix #4: PlayerActivity.onHideCustomView NPE after setContentView ==="

python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java", "r") as f:
    src = f.read()

# The problem: after setContentView(R.layout.activity_player), setupViews() is called
# but webView is a field from the OLD layout — need to reassign after setContentView
old = (
    '            public void onHideCustomView() {\n'
    '                if (customView != null) {\n'
    '                    setContentView(R.layout.activity_player);\n'
    '                    setupViews();\n'
    '                    loadPlayer(currentServerUrl, currentServerUrlTv, "standard");\n'
    '                    customView = null;\n'
    '                }\n'
    '            }'
)
new = (
    '            public void onHideCustomView() {\n'
    '                if (customView != null) {\n'
    '                    customView = null;\n'
    '                    // Restore layout and re-bind views before loading player\n'
    '                    setContentView(R.layout.activity_player);\n'
    '                    webView        = findViewById(R.id.player_webview);\n'
    '                    loadingBar     = findViewById(R.id.player_loading_bar);\n'
    '                    playerTitle    = findViewById(R.id.player_title);\n'
    '                    loadingOverlay = findViewById(R.id.player_loading_overlay);\n'
    '                    setupViews();\n'
    '                    String fmt = getIntent().getStringExtra("server_url_format");\n'
    '                    loadPlayer(currentServerUrl, currentServerUrlTv,\n'
    '                        fmt != null ? fmt : "standard");\n'
    '                }\n'
    '            }'
)

if old in src:
    src = src.replace(old, new, 1)
    print("  onHideCustomView: fixed")
else:
    print("  onHideCustomView: pattern not found - check manually")

with open("app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java", "w") as f:
    f.write(src)
print("  PlayerActivity.java saved.")
PYEOF

echo ""
echo "✅ Batch 1 done! Run:"
echo "   git add -A && git commit -m 'Fix batch 1: response.body() NPE, getInstance context, onHideCustomView' && git push"
