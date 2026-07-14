package com.neroflix.tv.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;

import com.bumptech.glide.Glide;
import com.neroflix.tv.app.R;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class AnnouncementChecker {

    private static final String TAG = "AnnouncementChecker";
    private static final String JSON_URL =
        "https://raw.githubusercontent.com/Sasuw30/PureNeroflixv2/refs/heads/main/announcement.json";
    private static final String PREFS = "nero_prefs";
    private static final String KEY_LAST_SEEN_ID = "last_announcement_id";

    public static void check(Activity activity) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(JSON_URL).openConnection();
                conn.setRequestProperty("User-Agent", "PureNeroflix-App");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                if (conn.getResponseCode() != 200) { conn.disconnect(); return; }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                boolean show = json.optBoolean("show", false);
                int id = json.optInt("id", 0);
                String title = json.optString("title", "Announcement");
                String message = json.optString("message", "");
                String imageUrl = json.optString("image", "");

                if (!show || message.isEmpty()) return;

                SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                int lastSeenId = prefs.getInt(KEY_LAST_SEEN_ID, -1);
                if (id <= lastSeenId) return;

                prefs.edit().putInt(KEY_LAST_SEEN_ID, id).apply();

                final String finalImageUrl = imageUrl;
                final String finalTitle = title;
                final String finalMessage = message;

                activity.runOnUiThread(() ->
                    showDialog(activity, finalTitle, finalMessage, finalImageUrl));

            } catch (Exception e) {
                Log.e(TAG, "Announcement check failed", e);
            }
        }).start();
    }

    private static void showDialog(Activity activity, String title, String message, String imageUrl) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_announcement, null);

        TextView titleView = view.findViewById(R.id.announcement_title_text);
        TextView msg = view.findViewById(R.id.announcement_message);
        ImageView img = view.findViewById(R.id.announcement_image);
        android.view.View imgCard = view.findViewById(R.id.announcement_image_card);

        titleView.setText(title);
        msg.setText(message);

        if (!imageUrl.isEmpty()) {
            imgCard.setVisibility(View.VISIBLE);
            Glide.with(activity)
                .load(imageUrl)
                .into(img);
        } else {
            imgCard.setVisibility(View.GONE);
        }

        new AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("Got it", null)
            .setCancelable(false)
            .show();
    }
}
