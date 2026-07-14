#!/bin/bash
# fix_logo_cache.sh — Permanently cache network/studio logo_path lookups +
# ensure Glide disk-caches the actual logo images, so logos load instantly
# on every app reopen instead of re-fetching from TMDB every time.
# Run from repo root: bash fix_logo_cache.sh
set -e

echo "=== [1/2] TmdbClient — persist logo_path lookups to SharedPreferences ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/network/TmdbClient.java", "r") as f:
    src = f.read()

# Replace fetchLogoById entirely with a version that:
#  1. Checks SharedPreferences first (survives app restarts, no expiry —
#     network/studio logos essentially never change)
#  2. Falls back to in-memory jsonCache (fast path within same session)
#  3. Only hits the network if neither cache has it
#  4. On success, writes to BOTH caches so future calls (this session and
#     future app launches) skip the network entirely

old_method = '''    private void fetchLogoById(String path, NetworkCallback callback) {
        String url = BASE_URL + path + "?api_key=" + API_KEY;

        // Cache the raw JSON string for network calls too
        if (jsonCache.containsKey(url)) {
            String cached = jsonCache.get(url);
            try {
                String logoPath = new JSONObject(cached).optString("logo_path", "");
                mainHandler.post(() -> callback.onSuccess(logoPath));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
            return;
        }

        executor.execute(() -> {
            try {
                Request request = new Request.Builder().url(url).build();
                Response response = httpClient.newCall(request).execute();
                okhttp3.ResponseBody rb = response.body();
                if (rb == null) { mainHandler.post(() -> callback.onError("Empty response")); return; }
                String body = rb.string();
                jsonCache.put(url, body);
                JSONObject json = new JSONObject(body);
                String logoPath = json.optString("logo_path", "");
                mainHandler.post(() -> callback.onSuccess(logoPath));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }'''

new_method = '''    // Persistent prefs file just for network/studio logo paths — these
    // essentially never change, so unlike movie lists this cache has NO
    // expiry. Once resolved, a logo is never re-fetched from TMDB again.
    private static final String LOGO_PREFS = "tmdb_logo_cache";

    private void fetchLogoById(String path, NetworkCallback callback) {
        String url = BASE_URL + path + "?api_key=" + API_KEY;

        // 1. In-memory hit — fastest path, valid for this process lifetime
        if (jsonCache.containsKey(url)) {
            String cached = jsonCache.get(url);
            try {
                String logoPath = new JSONObject(cached).optString("logo_path", "");
                mainHandler.post(() -> callback.onSuccess(logoPath));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
            return;
        }

        // 2. Persistent disk hit — survives app restarts, no expiry
        if (context != null) {
            String diskCached = context
                .getSharedPreferences(LOGO_PREFS, Context.MODE_PRIVATE)
                .getString(url, null);
            if (diskCached != null) {
                jsonCache.put(url, diskCached); // promote to memory too
                try {
                    String logoPath = new JSONObject(diskCached).optString("logo_path", "");
                    mainHandler.post(() -> callback.onSuccess(logoPath));
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onError(e.getMessage()));
                }
                return;
            }
        }

        // 3. Neither cache has it — fetch from network once, then persist
        //    permanently so this exact lookup never happens again.
        executor.execute(() -> {
            try {
                Request request = new Request.Builder().url(url).build();
                Response response = httpClient.newCall(request).execute();
                okhttp3.ResponseBody rb = response.body();
                if (rb == null) { mainHandler.post(() -> callback.onError("Empty response")); return; }
                String body = rb.string();
                jsonCache.put(url, body);

                // Persist to disk so it survives app restarts — fire and forget
                if (context != null) {
                    context.getSharedPreferences(LOGO_PREFS, Context.MODE_PRIVATE)
                        .edit().putString(url, body).apply();
                }

                JSONObject json = new JSONObject(body);
                String logoPath = json.optString("logo_path", "");
                mainHandler.post(() -> callback.onSuccess(logoPath));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }'''

if old_method in src:
    src = src.replace(old_method, new_method, 1)
    print("  fetchLogoById: now permanently caches logo_path lookups to disk")
else:
    print("  Method pattern not found — may already be patched (check Batch 1 fix). Trying fallback pattern...")
    # Fallback: older unpatched version without the null-check fix
    old_fallback = '''    private void fetchLogoById(String path, NetworkCallback callback) {
        String url = BASE_URL + path + "?api_key=" + API_KEY;

        // Cache the raw JSON string for network calls too
        if (jsonCache.containsKey(url)) {
            String cached = jsonCache.get(url);
            try {
                String logoPath = new JSONObject(cached).optString("logo_path", "");
                mainHandler.post(() -> callback.onSuccess(logoPath));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
            return;
        }

        executor.execute(() -> {
            try {
                Request request = new Request.Builder().url(url).build();
                Response response = httpClient.newCall(request).execute();
                String body = response.body().string();
                jsonCache.put(url, body);
                JSONObject json = new JSONObject(body);
                String logoPath = json.optString("logo_path", "");
                mainHandler.post(() -> callback.onSuccess(logoPath));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }'''
    if old_fallback in src:
        src = src.replace(old_fallback, new_method, 1)
        print("  fetchLogoById: patched (fallback pattern matched)")
    else:
        print("  ERROR: neither pattern matched — manual edit needed")

with open("app/src/main/java/com/neroflix/tv/app/network/TmdbClient.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [2/2] NetworkLogoAdapter — ensure Glide disk-caches the actual logo images ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/adapters/NetworkLogoAdapter.java", "r") as f:
    src = f.read()

# Add DiskCacheStrategy.ALL to both Glide.load() calls so the actual PNG/SVG
# bytes are cached permanently on disk too -- not just the logo_path lookup.
# Without this, Glide may only keep a resized bitmap in its small memory
# cache, which is wiped on every app restart, forcing a re-download.

old_import = "import com.bumptech.glide.Glide;"
new_import = (
    "import com.bumptech.glide.Glide;\n"
    "import com.bumptech.glide.load.engine.DiskCacheStrategy;"
)
if old_import in src and "DiskCacheStrategy" not in src.split(old_import)[1][:200]:
    src = src.replace(old_import, new_import, 1)
    print("  DiskCacheStrategy import added")

old_load1 = (
    'Glide.with(context).load(logoUrl).placeholder(android.R.color.darker_gray).fitCenter().into(holder.logo);'
)
new_load1 = (
    'Glide.with(context).load(logoUrl)\n'
    '                        .diskCacheStrategy(DiskCacheStrategy.ALL)\n'
    '                        .placeholder(android.R.color.darker_gray)\n'
    '                        .fitCenter()\n'
    '                        .into(holder.logo);'
)
count1 = src.count(old_load1)
if count1 > 0:
    src = src.replace(old_load1, new_load1)
    print(f"  Glide.load (TMDB logo): DiskCacheStrategy.ALL added ({count1}x)")

old_load2 = (
    'Glide.with(context).load(network[2]).placeholder(android.R.color.darker_gray).fitCenter().into(holder.logo);'
)
new_load2 = (
    'Glide.with(context).load(network[2])\n'
    '                    .diskCacheStrategy(DiskCacheStrategy.ALL)\n'
    '                    .placeholder(android.R.color.darker_gray)\n'
    '                    .fitCenter()\n'
    '                    .into(holder.logo);'
)
count2 = src.count(old_load2)
if count2 > 0:
    src = src.replace(old_load2, new_load2)
    print(f"  Glide.load (fallback logo): DiskCacheStrategy.ALL added ({count2}x)")

with open("app/src/main/java/com/neroflix/tv/app/adapters/NetworkLogoAdapter.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "✅ Done! Run:"
echo "   git add -A && git commit -m 'Permanently cache network/studio logos to disk — no re-fetch on app reopen' && git push"
