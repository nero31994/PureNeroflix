package com.neroflix.tv.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.neroflix.tv.app.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MyDownloadsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_downloads);
        loadDownloads();
        findViewById(R.id.downloads_back_btn).setOnClickListener(v -> finish());
    }

    private void loadDownloads() {
        LinearLayout list = findViewById(R.id.downloads_list);
        list.removeAllViews();

        File dir = new File(getExternalFilesDir(null), "NeroFlix");
        if (!dir.exists() || dir.listFiles() == null) {
            TextView empty = new TextView(this);
            empty.setText("No downloads yet.");
            empty.setTextColor(0xFFAAAAAA);
            empty.setPadding(32, 32, 32, 32);
            list.addView(empty);
            return;
        }

        List<File> files = new ArrayList<>();
        for (File f : dir.listFiles()) {
            if (f.getName().endsWith(".mp4") || f.getName().endsWith(".mkv")) {
                files.add(f);
            }
        }

        if (files.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No downloads yet.");
            empty.setTextColor(0xFFAAAAAA);
            empty.setPadding(32, 32, 32, 32);
            list.addView(empty);
            return;
        }

        for (File file : files) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setPadding(24, 16, 24, 16);
            item.setFocusable(true);
            item.setBackground(getDrawable(R.drawable.nav_item_focus_bg));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView title = new TextView(this);
            title.setText(file.getName().replace("_", " ").replaceAll("\\.(mp4|mkv)$", ""));
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(14);

            TextView size = new TextView(this);
            size.setText(formatSize(file.length()));
            size.setTextColor(0xFF888888);
            size.setTextSize(11);

            info.addView(title);
            info.addView(size);

            TextView playBtn = new TextView(this);
            playBtn.setText("▶ Play");
            playBtn.setTextColor(0xFFFFFFFF);
            playBtn.setTextSize(13);
            playBtn.setPadding(16, 8, 16, 8);
            playBtn.setFocusable(true);
            playBtn.setBackground(getDrawable(R.drawable.nav_item_focus_bg));

            TextView deleteBtn = new TextView(this);
            deleteBtn.setText("🗑");
            deleteBtn.setTextSize(16);
            deleteBtn.setPadding(16, 8, 16, 8);
            deleteBtn.setFocusable(true);

            final File f = file;
            final String name = title.getText().toString();
            playBtn.setOnClickListener(v -> {
                Intent intent = new Intent(this, LocalPlayerActivity.class);
                intent.putExtra("file_path", f.getAbsolutePath());
                intent.putExtra("movie_title", name);
                startActivity(intent);
            });
            deleteBtn.setOnClickListener(v -> {
                f.delete();
                loadDownloads();
            });

            item.addView(info);
            item.addView(playBtn);
            item.addView(deleteBtn);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, 4);
            item.setLayoutParams(params);
            list.addView(item);
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        return super.onKeyDown(keyCode, event);
    }
}
