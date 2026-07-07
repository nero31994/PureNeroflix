package com.neroflix.tv.app.activities;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.widget.NumberPicker;
import android.widget.LinearLayout;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.models.Movie;
import com.neroflix.tv.app.network.TmdbClient;

public class DetailActivity extends BaseTvActivity {

    private int movieId;
    private String mediaType;
    private Movie movie;

    private ImageView backdropImage;
    private ImageView posterImage;
    private TextView titleText;
    private TextView overviewText;
    private TextView ratingText;
    private TextView yearText;
    private TextView genresText;
    private TextView runtimeText;
    private TextView taglineText;
    private View playButton;
    private View backButton;
    private ProgressBar progressBar;







    private void showDialogWithDpadHidden(android.app.Dialog dialog) {
        dialog.setOnDismissListener(d -> {
        });
        dialog.show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        movieId = getIntent().getIntExtra("movie_id", 0);
        mediaType = getIntent().getStringExtra("media_type");

        setupViews();
        loadBasicInfo();
        loadDetailInfo();
    }

    private void setupViews() {
        backdropImage = findViewById(R.id.detail_backdrop);
        posterImage   = findViewById(R.id.detail_poster);
        titleText     = findViewById(R.id.detail_title);
        overviewText  = findViewById(R.id.detail_overview);
        ratingText    = findViewById(R.id.detail_rating);
        yearText      = findViewById(R.id.detail_year);
        genresText    = findViewById(R.id.detail_genres);
        runtimeText   = findViewById(R.id.detail_runtime);
        taglineText   = findViewById(R.id.detail_tagline);
        playButton    = findViewById(R.id.detail_play_btn);
        backButton    = findViewById(R.id.detail_back_btn);
        progressBar   = findViewById(R.id.detail_progress);

        playButton.setFocusable(true);
        backButton.setFocusable(true);

        playButton.setOnClickListener(v -> playMovie());

        View downloadButton = findViewById(R.id.detail_download_btn);
        if (downloadButton != null) {
            downloadButton.setFocusable(true);
            downloadButton.setOnClickListener(v -> openDownload());
            setFocusAnimation(downloadButton);
            downloadButton.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    openDownload(); return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    playButton.requestFocus(); return true;
                }
                return false;
            });
        }

        View watchlistBtn = findViewById(R.id.detail_watchlist_btn);
        if (watchlistBtn != null) {
            watchlistBtn.setFocusable(true);
            updateWatchlistBtn(watchlistBtn);
            watchlistBtn.setOnClickListener(v -> {
                if (movie != null) {
                    com.neroflix.tv.app.WatchManager.toggleWatchlist(this, movie);
                    updateWatchlistBtn(watchlistBtn);
                }
            });
            setFocusAnimation(watchlistBtn);
            watchlistBtn.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    if (movie != null) {
                        com.neroflix.tv.app.WatchManager.toggleWatchlist(this, movie);
                        updateWatchlistBtn(watchlistBtn);
                    }
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    playButton.requestFocus(); return true;
                }
                return false;
            });
        }

        backButton.setOnClickListener(v -> finish());

        // D-pad navigation between buttons: Back ← Play → Download → Watchlist
        playButton.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    playMovie(); return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    backButton.requestFocus(); return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    View dl = findViewById(R.id.detail_download_btn);
                    if (dl != null) { dl.requestFocus(); return true; }
                }
            }
            return false;
        });

        backButton.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    finish(); return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    playButton.requestFocus(); return true;
                }
            }
            return false;
        });

        setFocusAnimation(playButton);
        setFocusAnimation(backButton);

        // Auto-focus play button on open
        playButton.requestFocus();
    }

    private void setFocusAnimation(View view) {
        view.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.05f : 1f)
                    .scaleY(hasFocus ? 1.05f : 1f)
                    .setDuration(120).start();
        });
    }

    private void loadBasicInfo() {
        String title        = getIntent().getStringExtra("movie_title");
        String overview     = getIntent().getStringExtra("movie_overview");
        String posterPath   = getIntent().getStringExtra("movie_poster");
        String backdropPath = getIntent().getStringExtra("movie_backdrop");
        String year         = getIntent().getStringExtra("movie_year");
        double rating       = getIntent().getDoubleExtra("movie_rating", 0.0);

        if (title != null)    titleText.setText(title);
        if (overview != null) overviewText.setText(overview);
        if (year != null)     yearText.setText(year);
        ratingText.setText(String.format("★ %.1f", rating));

        if (backdropPath != null && !backdropPath.isEmpty()) {
            Glide.with(this)
                    .load("https://image.tmdb.org/t/p/w1280" + backdropPath)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .centerCrop()
                    .into(backdropImage);
        }

        if (posterPath != null && !posterPath.isEmpty()) {
            Glide.with(this)
                    .load("https://image.tmdb.org/t/p/w500" + posterPath)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .centerCrop()
                    .into(posterImage);
        }
    }

    private void loadDetailInfo() {
        progressBar.setVisibility(View.VISIBLE);
        TmdbClient.getInstance(this).fetchMovieDetail(movieId, mediaType, new TmdbClient.MovieDetailCallback() {
            @Override
            public void onSuccess(Movie m) {
                movie = m;
                progressBar.setVisibility(View.GONE);
                updateDetailUI(m);
            }
            @Override
            public void onError(String error) {
                progressBar.setVisibility(View.GONE);
            }
        });
    }

    private void updateDetailUI(Movie m) {
        if (m.getGenres() != null && !m.getGenres().isEmpty()) {
            genresText.setText(m.getGenres());
            genresText.setVisibility(View.VISIBLE);
        }
        if (!m.getRuntimeFormatted().isEmpty()) {
            runtimeText.setText(m.getRuntimeFormatted());
            runtimeText.setVisibility(View.VISIBLE);
        }
        if (m.getTagline() != null && !m.getTagline().isEmpty()) {
            taglineText.setText("\"" + m.getTagline() + "\"");
            taglineText.setVisibility(View.VISIBLE);
        }
    }

    private void playMovie() {
        if ("tv".equals(mediaType)) {
            showEpisodePicker();
        } else {
            launchPlayer(1, 1);
        }
    }

    private void showEpisodePicker() {
        AlertDialog loadingDialog = new AlertDialog.Builder(this)
            .setTitle("Loading seasons...")
            .setMessage("Please wait")
            .setCancelable(false)
            .create();
        loadingDialog.show();

        com.neroflix.tv.app.network.TmdbClient.getInstance(this).fetchTVDetails(movieId,
            new com.neroflix.tv.app.network.TmdbClient.TVDetailsCallback() {
                @Override
                public void onSuccess(int numSeasons, java.util.List<String> seasonNames) {
                    if (!isFinishing() && !isDestroyed() && loadingDialog.isShowing()) loadingDialog.dismiss();
                    showSeasonDialog(numSeasons, seasonNames);
                }
                @Override
                public void onError(String error) {
                    if (!isFinishing() && !isDestroyed() && loadingDialog.isShowing()) loadingDialog.dismiss();
                    showSimpleEpisodePicker();
                }
            });
    }

    private void showSeasonDialog(int numSeasons, java.util.List<String> seasonNames) {
        String[] seasons = seasonNames.toArray(new String[0]);
        new AlertDialog.Builder(this)
            .setTitle("Select Season")
            .setItems(seasons, (d, which) -> fetchEpisodesForSeason(which + 1))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void fetchEpisodesForSeason(int season) {
        AlertDialog loadingDialog = new AlertDialog.Builder(this)
            .setTitle("Loading episodes...")
            .setMessage("Season " + season)
            .setCancelable(false)
            .create();
        loadingDialog.show();

        com.neroflix.tv.app.network.TmdbClient.getInstance(this).fetchEpisodes(movieId, season,
            new com.neroflix.tv.app.network.TmdbClient.EpisodesCallback() {
                @Override
                public void onSuccess(java.util.List<String> episodeNames) {
                    if (!isFinishing() && !isDestroyed() && loadingDialog.isShowing()) loadingDialog.dismiss();
                    String[] episodes = episodeNames.toArray(new String[0]);
                    new AlertDialog.Builder(DetailActivity.this)
                        .setTitle("Season " + season + " — Select Episode")
                        .setItems(episodes, (d, which) -> launchPlayer(season, which + 1))
                        .setNegativeButton("Cancel", null)
                        .show();
                }
                @Override
                public void onError(String error) {
                    if (!isFinishing() && !isDestroyed() && loadingDialog.isShowing()) loadingDialog.dismiss();
                    showSimpleEpisodePicker();
                }
            });
    }

    private void showSimpleEpisodePicker() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        layout.setPadding(40, 20, 40, 20);
        layout.setGravity(android.view.Gravity.CENTER);
        android.widget.NumberPicker seasonPicker = new android.widget.NumberPicker(this);
        seasonPicker.setMinValue(1);
        seasonPicker.setMaxValue(15);
        android.widget.NumberPicker episodePicker = new android.widget.NumberPicker(this);
        episodePicker.setMinValue(1);
        episodePicker.setMaxValue(30);
        layout.addView(seasonPicker);
        layout.addView(episodePicker);
        new AlertDialog.Builder(this)
            .setTitle("Select Episode")
            .setView(layout)
            .setPositiveButton("Watch", (d, w) -> launchPlayer(seasonPicker.getValue(), episodePicker.getValue()))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void launchPlayer(int season, int episode) {
        // Show a loading indicator while we contact the server
        runOnUiThread(() -> progressBar.setVisibility(View.VISIBLE));

        // fetchServers contacts the Worker — server URLs are never in the APK.
        // Returns null if device is not approved.
        com.neroflix.tv.app.LicenseManager.fetchServers(this, servers -> {
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);

                if (servers == null || servers.length == 0) {
                    // Not activated — send to ActivationActivity
                    startActivity(new Intent(this,
                        com.neroflix.tv.app.activities.ActivationActivity.class));
                    return;
                }

                if (servers.length == 1) {
                    launchPlayerIntent(movieId, mediaType, season, episode, 0,
                        servers[0][1], servers[0][2], servers[0][3]);
                    return;
                }

                String[] labels = new String[servers.length];
                for (int i = 0; i < servers.length; i++) labels[i] = servers[i][0];

                final String[][] finalServers = servers;
                new AlertDialog.Builder(this)
                    .setTitle("Select Server")
                    .setItems(labels, (d, which) ->
                        launchPlayerIntent(movieId, mediaType, season, episode,
                            which, finalServers[which][1], finalServers[which][2],
                            finalServers[which].length > 3 ? finalServers[which][3] : "standard"))
                    .show();
            });
        });
    }

    private void launchPlayerIntent(int movieId, String mediaType,
                                    int season, int episode,
                                    int serverIndex, String serverUrl, String serverUrlTv, String serverUrlFormat) {

        // ── Yastream servers: ExoPlayer m3u8 player ──────────────────────────
        if ("yastream".equals(serverUrlFormat) || "yastream_onetouchtv".equals(serverUrlFormat)) {
            String title = (movie != null) ? movie.getTitle()
                    : getIntent().getStringExtra("movie_title");
            final String subMediaType = mediaType;
            final int subTmdbId = movieId;
            final int subSeason = season;
            final int subEpisode = episode;
            final String subTitle = (movie != null) ? movie.getTitle() : getIntent().getStringExtra("movie_title");
            // Fetch subtitle on background, then launch player
            new Thread(() -> {
                String subtitleUrl = null;
                try {
                    String extType = "movie".equals(subMediaType) ? "movie" : "tv";
                    String extUrl = "https://api.themoviedb.org/3/" + extType + "/" + subTmdbId
                        + "/external_ids?api_key=" + com.neroflix.tv.app.BuildConfig.TMDB_API_KEY;
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(extUrl).openConnection();
                    conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String ln;
                    while ((ln = br.readLine()) != null) sb.append(ln);
                    String imdbId = new org.json.JSONObject(sb.toString()).optString("imdb_id", "");
                    if (!imdbId.isEmpty()) {
                        String yasType = "movie".equals(subMediaType) ? "movie" : "series";
                        String yasId = imdbId;
                        if (!"movie".equals(subMediaType) && subSeason > 0 && subEpisode > 0)
                            yasId += ":" + subSeason + ":" + subEpisode;
                        String yasConfig = "eyJjYXRhbG9ncyI6WyJraXNza2guc2VyaWVzLktvcmVhbiIsImtpc3NraC5tb3ZpZS5Lb3JlYW4iLCJraXNza2gubW92aWUuQ2hpbmVzZSIsImtpc3NraC5zZXJpZXMuQ2hpbmVzZSIsImtpc3NraC5tb3ZpZS5VUyIsImtpc3NraC5zZXJpZXMuVVMiLCJraXNza2gubW92aWUuVGhhaSIsImtpc3NraC5zZXJpZXMuVGhhaSIsImtpc3NraC5tb3ZpZS5QaGlsaXBwaW5lIiwia2lzc2toLnNlcmllcy5QaGlsaXBwaW5lIiwia2lzc2toLm1vdmllLkphcGFuZXNlIiwia2lzc2toLnNlcmllcy5KYXBhbmVzZSIsImtpc3NraC5tb3ZpZS5Ib25na29uZyIsImtpc3NraC5zZXJpZXMuSG9uZ2tvbmciLCJraXNza2gubW92aWUuVGFpd2FuZXNlIiwia2lzc2toLnNlcmllcy5UYWl3YW5lc2UiLCJvbmV0b3VjaHR2LnNlcmllcy5Lb3JlYW4iLCJvbmV0b3VjaHR2LnNlcmllcy5Qb3B1bGFyIiwib25ldG91Y2h0di5zZXJpZXMuQ2hpbmVzZSIsIm9uZXRvdWNodHYuc2VyaWVzLlRoYWkiLCJraXNza2guc2VyaWVzLlNlYXJjaCIsImtpc3NraC5tb3ZpZS5TZWFyY2giLCJvbmV0b3VjaHR2LnNlcmllcy5TZWFyY2giLCJpZHJhbWEuc2VyaWVzLmlEcmFtYSIsImlkcmFtYS5zZXJpZXMuU2VhcmNoIl0sImNhdGFsb2ciOlsia2lzc2toIiwib25ldG91Y2h0diIsImlkcmFtYSJdLCJzdHJlYW0iOlsia2lzc2toIiwib25ldG91Y2h0diIsImlkcmFtYSIsImtrcGhpbSJdLCJuc2Z3IjpmYWxzZSwiaW5mbyI6ZmFsc2UsInBvc3RlciI6InJwZGIiLCJtZnBVcmwiOiIiLCJ0YktleSI6IiIsIm1mcFBhc3MiOiIifQ==";
                        String yasUrl = "https://yastream.tamthai.de/subtitles/" + yasType + "/" + yasId + ".json?config=" + yasConfig;
                        java.net.HttpURLConnection c2 = (java.net.HttpURLConnection) new java.net.URL(yasUrl).openConnection();
                        c2.setConnectTimeout(8000); c2.setReadTimeout(8000);
                        java.io.BufferedReader br2 = new java.io.BufferedReader(new java.io.InputStreamReader(c2.getInputStream()));
                        StringBuilder sb2 = new StringBuilder();
                        while ((ln = br2.readLine()) != null) sb2.append(ln);
                        org.json.JSONArray arr = new org.json.JSONObject(sb2.toString()).optJSONArray("subtitles");
                        if (arr != null) {
                            // Prefer tgl (Filipino), fallback to eng
                            for (int si = 0; si < arr.length(); si++) {
                                org.json.JSONObject s = arr.getJSONObject(si);
                                if ("tgl".equals(s.optString("lang", ""))) {
                                    subtitleUrl = s.optString("url", ""); break;
                                }
                            }
                            if (subtitleUrl == null || subtitleUrl.isEmpty()) {
                                for (int si = 0; si < arr.length(); si++) {
                                    org.json.JSONObject s = arr.getJSONObject(si);
                                    if ("eng".equals(s.optString("lang", ""))) {
                                        subtitleUrl = s.optString("url", ""); break;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w("Detail", "Subtitle fetch failed: " + e.getMessage());
                }
                final String finalSubUrl = subtitleUrl;
                runOnUiThread(() -> {
                    Intent intent = new Intent(DetailActivity.this, com.neroflix.tv.app.activities.YastreamPlayerActivity.class);
                    intent.putExtra("movie_id",       subTmdbId);
                    intent.putExtra("media_type",     subMediaType);
                    intent.putExtra("movie_title",    subTitle);
                    intent.putExtra("season",         subSeason);
                    intent.putExtra("episode",        subEpisode);
                    if (finalSubUrl != null && !finalSubUrl.isEmpty())
                        intent.putExtra("direct_subtitle_url", finalSubUrl);
                    startActivity(intent);
                    overridePendingTransition(0, 0); // no transition — player starts black
                });
            }).start();
            return;
        }

        // ── Standard / AnyEmbed servers: WebView PlayerActivity ──────────────
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("movie_id",          movieId);
        intent.putExtra("media_type",        mediaType);
        intent.putExtra("season",            season);
        intent.putExtra("episode",           episode);
        intent.putExtra("server_index",      serverIndex);
        intent.putExtra("server_url",        serverUrl);
        intent.putExtra("server_url_tv",     serverUrlTv != null ? serverUrlTv : serverUrl);
        // Pass movie art for the loading screen
        intent.putExtra("movie_poster",   getIntent().getStringExtra("movie_poster"));
        intent.putExtra("movie_backdrop", getIntent().getStringExtra("movie_backdrop"));
        intent.putExtra("movie_title",    getIntent().getStringExtra("movie_title"));
        intent.putExtra("server_url_format", serverUrlFormat != null ? serverUrlFormat : "standard");
        String title = (movie != null) ? movie.getTitle()
                : getIntent().getStringExtra("movie_title");
        intent.putExtra("movie_title", title);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    // Detail screen focus: 0=Back, 1=Play, 2=Download, 3=Watchlist
    private int detailFocusIndex = 1;
    private final int[] DETAIL_BTN_IDS = {
        R.id.detail_back_btn,
        R.id.detail_play_btn,
        R.id.detail_download_btn,
        R.id.detail_watchlist_btn
    };

    private void highlightDetailBtn(int index) {
        detailFocusIndex = Math.max(0, Math.min(index, DETAIL_BTN_IDS.length - 1));
        for (int i = 0; i < DETAIL_BTN_IDS.length; i++) {
            android.view.View btn = findViewById(DETAIL_BTN_IDS[i]);
            if (btn == null) continue;
            if (i == detailFocusIndex) {
                // Scale up + elevation only - don't replace background
                btn.setScaleX(1.06f);
                btn.setScaleY(1.06f);
                btn.setElevation(12f);
                btn.setAlpha(1f);
            } else {
                btn.setScaleX(1f);
                btn.setScaleY(1f);
                btn.setElevation(2f);
                btn.setAlpha(0.75f);
            }
        }
    }

        @Override
    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                finish();
                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                highlightDetailBtn(detailFocusIndex + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                highlightDetailBtn(detailFocusIndex - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                android.view.View btn = findViewById(DETAIL_BTN_IDS[detailFocusIndex]);
                if (btn != null) btn.performClick();
                return true;
        }
        return false; // fallback now handled by BaseTvActivity
    }

    private void openDownload() {
        com.neroflix.tv.app.LicenseManager.fetchServers(this, servers -> {
            runOnUiThread(() -> {
                if (servers == null || !com.neroflix.tv.app.LicenseManager.isPremium(this)) {
                    // Free plan or not activated — no download
                    new AlertDialog.Builder(this)
                        .setTitle("🔒 Premium Required")
                        .setMessage("Download feature is available for Premium users only.\n\nContact admin to upgrade.")
                        .setPositiveButton("OK", null)
                        .show();
                    return;
                }
                if ("tv".equals(mediaType)) {
                    showEpisodePickerForDownload();
                } else {
                    launchDownload(1, 1);
                }
            });
        });
    }

    private void showEpisodePickerForDownload() {
        AlertDialog loadingDialog = new AlertDialog.Builder(this)
            .setTitle("Loading seasons...")
            .setMessage("Please wait")
            .setCancelable(false)
            .create();
        loadingDialog.show();

        com.neroflix.tv.app.network.TmdbClient.getInstance(this).fetchTVDetails(movieId,
            new com.neroflix.tv.app.network.TmdbClient.TVDetailsCallback() {
                @Override
                public void onSuccess(int numSeasons, java.util.List<String> seasonNames) {
                    if (!isFinishing() && !isDestroyed() && loadingDialog.isShowing()) loadingDialog.dismiss();
                    String[] seasons = seasonNames.toArray(new String[0]);
                    new AlertDialog.Builder(DetailActivity.this)
                        .setTitle("Select Season to Download")
                        .setItems(seasons, (d, which) -> fetchEpisodesForDownload(which + 1))
                        .setNegativeButton("Cancel", null)
                        .show();
                }
                @Override
                public void onError(String error) {
                    if (!isFinishing() && !isDestroyed() && loadingDialog.isShowing()) loadingDialog.dismiss();
                    android.widget.NumberPicker sp = new android.widget.NumberPicker(DetailActivity.this);
                    sp.setMinValue(1); sp.setMaxValue(15);
                    android.widget.NumberPicker ep = new android.widget.NumberPicker(DetailActivity.this);
                    ep.setMinValue(1); ep.setMaxValue(30);
                    android.widget.LinearLayout layout = new android.widget.LinearLayout(DetailActivity.this);
                    layout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                    layout.setPadding(40, 20, 40, 20);
                    layout.setGravity(android.view.Gravity.CENTER);
                    layout.addView(sp); layout.addView(ep);
                    new AlertDialog.Builder(DetailActivity.this)
                        .setTitle("Select Episode to Download")
                        .setView(layout)
                        .setPositiveButton("Download", (d, w) -> launchDownload(sp.getValue(), ep.getValue()))
                        .setNegativeButton("Cancel", null)
                        .show();
                }
            });
    }

    private void fetchEpisodesForDownload(int season) {
        AlertDialog loadingDialog = new AlertDialog.Builder(this)
            .setTitle("Loading episodes...")
            .setMessage("Season " + season)
            .setCancelable(false)
            .create();
        loadingDialog.show();

        com.neroflix.tv.app.network.TmdbClient.getInstance(this).fetchEpisodes(movieId, season,
            new com.neroflix.tv.app.network.TmdbClient.EpisodesCallback() {
                @Override
                public void onSuccess(java.util.List<String> episodeNames) {
                    if (!isFinishing() && !isDestroyed() && loadingDialog.isShowing()) loadingDialog.dismiss();
                    String[] episodes = episodeNames.toArray(new String[0]);
                    new AlertDialog.Builder(DetailActivity.this)
                        .setTitle("Season " + season + " — Select Episode")
                        .setItems(episodes, (d, which) -> launchDownload(season, which + 1))
                        .setNegativeButton("Cancel", null)
                        .show();
                }
                @Override
                public void onError(String error) {
                    if (!isFinishing() && !isDestroyed() && loadingDialog.isShowing()) loadingDialog.dismiss();
                    launchDownload(season, 1);
                }
            });
    }

    private void launchDownload(int season, int episode) {
        Intent intent = new Intent(this, DownloadActivity.class);
        intent.putExtra("movie_id",   movieId);
        intent.putExtra("media_type", mediaType);
        intent.putExtra("season",     season);
        intent.putExtra("episode",    episode);
        String title = (movie != null) ? movie.getTitle()
                : getIntent().getStringExtra("movie_title");
        intent.putExtra("movie_title", title);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void updateWatchlistBtn(android.view.View btn) {
        if (movie == null) return;
        boolean inList = com.neroflix.tv.app.WatchManager.isInWatchlist(this, movie);
        if (btn instanceof android.widget.Button) {
            ((android.widget.Button) btn).setText(inList ? "✓ In Watchlist" : "+ Watchlist");
        }
    }
}
