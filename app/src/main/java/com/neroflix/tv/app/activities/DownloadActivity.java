package com.neroflix.tv.app.activities;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.neroflix.tv.app.R;

public class DownloadActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusText;

    private int movieId;
    private String movieTitle;
    private String mediaType;
    private int season;
    private int episode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download);

        movieId    = getIntent().getIntExtra("movie_id", 0);
        movieTitle = getIntent().getStringExtra("movie_title");
        mediaType  = getIntent().getStringExtra("media_type");
        season     = getIntent().getIntExtra("season", 1);
        episode    = getIntent().getIntExtra("episode", 1);
        if (movieTitle == null) movieTitle = "movie";

        setupViews();
        loadDownloadPage();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupViews() {
        webView     = findViewById(R.id.download_webview);
        progressBar = findViewById(R.id.download_progress);
        statusText  = findViewById(R.id.download_status);

        TextView backBtn = findViewById(R.id.download_back_btn);
        backBtn.setOnClickListener(v -> finish());
        backBtn.setFocusable(true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36");
        settings.setAllowFileAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isDownloadUrl(url)) {
                    startDownload(url);
                    return true;
                }
                return false;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                // Inject D-pad key forwarding into the page
                view.evaluateJavascript(
                    "window.open=function(){return{focus:function(){},blur:function(){}}};" +
                    "window.alert=function(){};", null);
            }
        });

        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                progressBar.setProgress(progress);
                progressBar.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            startDownload(url);
        });

        // Make WebView focusable for D-pad
            // Make WebView focusable for D-pad
    webView.setFocusable(true);
    webView.setFocusableInTouchMode(true);
}   // ← ADD THIS CLOSING BRACE

private void loadDownloadPage() {
        String url;
        if ("tv".equals(mediaType)) {
            // FIX: deep-link directly to the specific season/episode
            url = "https://vidvault.ru/tv/" + movieId + "/" + season + "/" + episode;
        } else {
            url = "https://vidvault.ru/movie/" + movieId;
        }
        statusText.setText("Finding download links for: " + movieTitle);
        webView.loadUrl(url);
    }

    private boolean isDownloadUrl(String url) {
        return url.endsWith(".mp4") || url.endsWith(".mkv")
            || url.contains(".mp4?") || url.contains(".mkv?")
            || url.contains("workers.dev")
            || url.contains("hakunaymatata")
            || url.contains("bcdnxw")
            || url.contains("dl.") && (url.contains(".mp4") || url.contains(".mkv") || url.contains("file"))
            || (url.contains("download") && !url.contains("vidvault"));
    }

    private void startDownload(String url) {
        // Build a safe filename: title + S01E01 suffix for TV shows
        String fileName = movieTitle.replaceAll("[^a-zA-Z0-9]", "_");
        if ("tv".equals(mediaType)) {
            fileName += String.format("_S%02dE%02d", season, episode);
        }
        fileName += url.endsWith(".mkv") ? ".mkv" : ".mp4";

        java.io.File dir = new java.io.File(getExternalFilesDir(null), "NeroFlix");
        if (!dir.exists()) dir.mkdirs();
        java.io.File outFile = new java.io.File(dir, fileName);

        statusText.setText("⬇ Downloading: " + movieTitle);
        progressBar.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Download started...", Toast.LENGTH_SHORT).show();

        final String finalFileName = fileName;
        final String referer = "https://vidvault.ru/";

        new Thread(() -> {
            try {
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

                okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .header("Referer", referer)
                    .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
                    .header("Origin", "https://vidvault.ru")
                    .build();

                okhttp3.Response response = client.newCall(request).execute();
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        statusText.setText("❌ Server rejected download (HTTP " + response.code() + ")");
                        Toast.makeText(this, "Download failed: HTTP " + response.code(), Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                long total = response.body().contentLength();
                java.io.InputStream in = response.body().byteStream();
                java.io.FileOutputStream out = new java.io.FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                long downloaded = 0;
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    downloaded += read;
                    if (total > 0) {
                        final int pct = (int)(downloaded * 100 / total);
                        final String mb = String.format("%.1f / %.1f MB",
                            downloaded / 1048576.0, total / 1048576.0);
                        runOnUiThread(() -> {
                            progressBar.setProgress(pct);
                            statusText.setText("⬇ " + pct + "% — " + mb);
                        });
                    }
                }
                out.close();
                in.close();

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("✅ Downloaded: " + finalFileName);
                    Toast.makeText(this, "Download complete!", Toast.LENGTH_LONG).show();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("❌ Error: " + e.getMessage());
                    Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (webView.canGoBack()) webView.goBack();
                else finish();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                webView.scrollBy(0, -150);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                webView.scrollBy(0, 150);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                webView.scrollBy(-150, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                webView.scrollBy(150, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // Simulate click at center of screen
                webView.evaluateJavascript(
                    "var el = document.elementFromPoint(window.innerWidth/2, window.innerHeight/2);" +
                    "if(el) el.click();", null);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                webView.evaluateJavascript(
                    "var v=document.querySelector('video');if(v){if(v.paused)v.play();else v.pause();}", null);
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
    }
}
