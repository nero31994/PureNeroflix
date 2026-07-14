# NeroFlix Android TV App

A native Java Android app that streams movies and TV shows via TMDB + VidFast embed player. Built for Android TV / Haier TV with full D-pad remote support.

---

## 📱 Features

- **Netflix-style home screen** — Hero banner + horizontal category rows
- **12 curated categories** — Trending, Now Playing, Top Rated, Genre rows
- **Full movie detail page** — Backdrop, poster, genres, runtime, tagline
- **VidFast WebView player** — `vidfast.pro` embed, no ads/popups
- **D-pad remote navigation** — Full Android TV remote support
- **Search** — Multi-search across movies and TV shows
- **TV Show support** — Season/episode routing

---

## 🚀 Build via GitHub Actions

Every push to `main` automatically builds and releases a signed APK.

### Setup Steps

**1. Fork / clone this repo to GitHub**

**2. Get a TMDB API Key**
- Go to [themoviedb.org](https://www.themoviedb.org/settings/api)
- Create a free account → API → Request API Key (Developer)

**3. Set GitHub Secrets** (Settings → Secrets and variables → Actions)

| Secret | Value |
|--------|-------|
| `TMDB_API_KEY` | Your TMDB API key |
| `KEYSTORE_BASE64` | *(optional)* Base64-encoded keystore for signed release |
| `KEY_ALIAS` | *(optional)* Keystore key alias |
| `KEYSTORE_PASSWORD` | *(optional)* Keystore password |
| `KEY_PASSWORD` | *(optional)* Key password |

**4. Push to main → APK auto-builds**

Find the APK under:
- **Actions** tab → latest run → **Artifacts** (debug APK, always available)
- **Releases** section (auto-created after each push to main)

---

## 📺 Player URLs

```
Movies:  https://vidfast.pro/movie/{tmdb_id}?hideServer=true&fullscreenButton=false&poster=false&autoplay=true
TV:      https://vidfast.pro/tv/{tmdb_id}/{season}/{episode}?hideServer=true&fullscreenButton=false&poster=false&autoplay=true
```

---

## 🎮 Remote Key Bindings (Player)

| Key | Action |
|-----|--------|
| D-pad Center / Enter | Play / Pause |
| D-pad Right | Skip +10s |
| D-pad Left | Rewind -10s |
| D-pad Up | Volume up |
| D-pad Down | Volume down |
| Media Fast Forward | Skip +30s |
| Media Rewind | Rewind -30s |
| Back | Exit player |

---

## 🔧 Local Build

```bash
# Clone the repo
git clone https://github.com/YOUR_USERNAME/NeroFlix.git
cd NeroFlix

# Set your TMDB API key in gradle.properties
echo "TMDB_API_KEY=your_key_here" >> gradle.properties

# Build debug APK
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

---

## 📁 Project Structure

```
NeroFlix/
├── .github/workflows/build-apk.yml   ← GitHub Actions CI/CD
├── app/src/main/
│   ├── java/com/neroflix/app/
│   │   ├── activities/
│   │   │   ├── MainActivity.java     ← Home screen
│   │   │   ├── DetailActivity.java   ← Movie detail
│   │   │   ├── PlayerActivity.java   ← VidFast WebView player
│   │   │   └── SearchActivity.java   ← Search
│   │   ├── adapters/
│   │   │   ├── MovieCardAdapter.java
│   │   │   └── CategoryRowAdapter.java
│   │   ├── models/
│   │   │   ├── Movie.java
│   │   │   └── Category.java
│   │   └── network/
│   │       └── TmdbClient.java       ← TMDB API (OkHttp)
│   └── res/
│       ├── layout/                   ← All XML layouts
│       ├── drawable/                 ← Gradients, focus states
│       ├── values/                   ← Colors, strings, themes
│       └── anim/                     ← Transitions
└── build.gradle
```

---

## 📝 Notes

- Minimum SDK: Android 5.0 (API 21)
- Target SDK: Android 14 (API 34)
- Hardware acceleration enabled for smooth WebView playback
- `usesCleartextTraffic=true` — allows all embed sources
- Landscape orientation enforced for TV use
- Orbitron + Rajdhani fonts via Google Fonts (downloadable fonts)
