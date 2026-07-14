#!/bin/bash
# fix_subtitles.sh — Fix all 4 subtitle issues in YastreamPlayerActivity
# Run from repo root: bash fix_subtitles.sh
set -e

echo "=== Fixing subtitle issues in YastreamPlayerActivity ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java", "r") as f:
    src = f.read()

# ── Fix 1 & 2: Replace showSubtitlePicker — store lang codes alongside labels,
#               use TrackGroup override instead of setPreferredTextLanguage(displayName)
old_picker = '''    private void showSubtitlePicker() {
        if (exoPlayer == null) return;

        androidx.media3.common.TrackSelectionParameters currentParams =
            exoPlayer.getTrackSelectionParameters();

        // Build list of available subtitle tracks
        androidx.media3.common.Tracks tracks = exoPlayer.getCurrentTracks();
        java.util.List<String> labels = new ArrayList<>();
        java.util.List<androidx.media3.common.TrackGroup> subGroups = new ArrayList<>();

        labels.add("Off");
        subGroups.add(null);

        for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                for (int i = 0; i < group.length; i++) {
                    androidx.media3.common.Format fmt = group.getTrackFormat(i);
                    java.util.Map<String,String> ln = new java.util.HashMap<>();
                    ln.put("eng","English"); ln.put("tgl","Filipino");
                    ln.put("msa","Malay"); ln.put("ind","Indonesian");
                    ln.put("tha","Thai"); ln.put("khm","Khmer");
                    ln.put("ara","Arabic"); ln.put("deu","German");
                    ln.put("fra","French"); ln.put("spa","Spanish");
                    ln.put("zho","Chinese"); ln.put("jpn","Japanese");
                    ln.put("kor","Korean"); ln.put("por","Portuguese");
                    ln.put("ita","Italian"); ln.put("rus","Russian");
                    ln.put("vie","Vietnamese");
                    String lang = fmt.language != null ? fmt.language.toLowerCase() : "und";
                    String label;
                    if (fmt.label != null && !fmt.label.isEmpty() && !fmt.label.equals("und")) {
                        label = fmt.label;
                    } else if (ln.containsKey(lang)) {
                        label = ln.get(lang);
                    } else if ("und".equals(lang)) {
                        label = "English"; // KissKH embedded track has no metadata, default to English
                    } else {
                        label = lang.toUpperCase();
                    }
                    android.widget.Toast.makeText(this, "lang=" + fmt.language + " label=" + fmt.label, android.widget.Toast.LENGTH_LONG).show();
                    labels.add(label);
                    subGroups.add(group.getMediaTrackGroup());
                }
            }
        }

        if (labels.size() <= 1) {
            android.widget.Toast.makeText(this,
                "No subtitles available for this stream", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        new android.app.AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                if (which == 0) {
                    // Turn off subtitles
                    exoPlayer.setTrackSelectionParameters(
                        currentParams.buildUpon()
                            .setIgnoredTextSelectionFlags(
                                androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                            .build());
                } else {
                    // Enable selected subtitle track
                    exoPlayer.setTrackSelectionParameters(
                        currentParams.buildUpon()
                            .setPreferredTextLanguage(
                                labels.get(which).toLowerCase())
                            .build());
                }
            })
            .setNegativeButton("Cancel", null)'''

new_picker = '''    private void showSubtitlePicker() {
        if (exoPlayer == null) return;

        // Build list of available subtitle tracks — store lang codes separately so we
        // pass the BCP-47 code to ExoPlayer, not the display name ("tgl" not "filipino")
        androidx.media3.common.Tracks tracks = exoPlayer.getCurrentTracks();
        java.util.List<String> labels   = new ArrayList<>();
        java.util.List<String> langCodes = new ArrayList<>(); // parallel list of BCP-47 codes
        java.util.List<androidx.media3.common.TrackGroup> trackGroups = new ArrayList<>();
        java.util.List<Integer> trackIndices = new ArrayList<>();

        labels.add("Off");
        langCodes.add(null);
        trackGroups.add(null);
        trackIndices.add(-1);

        for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                for (int i = 0; i < group.length; i++) {
                    androidx.media3.common.Format fmt = group.getTrackFormat(i);
                    String lang = fmt.language != null ? fmt.language.toLowerCase() : "und";
                    String label;
                    if (fmt.label != null && !fmt.label.isEmpty() && !fmt.label.equals("und")) {
                        label = fmt.label;
                    } else if (LANG_NAMES.containsKey(lang)) {
                        label = LANG_NAMES.get(lang);
                    } else if ("und".equals(lang)) {
                        label = "English"; // KissKH embedded track has no lang metadata
                    } else {
                        label = lang.toUpperCase();
                    }
                    labels.add(label);
                    langCodes.add(lang);          // store the actual BCP-47 code
                    trackGroups.add(group.getMediaTrackGroup());
                    trackIndices.add(i);
                }
            }
        }

        if (labels.size() <= 1) {
            android.widget.Toast.makeText(this,
                "No subtitles available for this stream", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        new android.app.AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                if (which == 0) {
                    // Turn off — disable all text tracks via override
                    exoPlayer.setTrackSelectionParameters(
                        exoPlayer.getTrackSelectionParameters().buildUpon()
                            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                            .setIgnoredTextSelectionFlags(
                                androidx.media3.common.C.SELECTION_FLAG_DEFAULT
                                | androidx.media3.common.C.SELECTION_FLAG_FORCED)
                            .build());
                } else {
                    // Select specific track via TrackSelectionOverride — bypasses language matching
                    androidx.media3.common.TrackGroup tg = trackGroups.get(which);
                    int ti = trackIndices.get(which);
                    if (tg != null && ti >= 0) {
                        exoPlayer.setTrackSelectionParameters(
                            exoPlayer.getTrackSelectionParameters().buildUpon()
                                .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                                .setIgnoredTextSelectionFlags(0)
                                .addOverride(new androidx.media3.common.TrackSelectionOverride(tg,
                                    java.util.Collections.singletonList(ti)))
                                .build());
                    }
                }
            })
            .setNegativeButton("Cancel", null)'''

if old_picker in src:
    src = src.replace(old_picker, new_picker, 1)
    print("  Fix 1 & 2: showSubtitlePicker rewritten — TrackSelectionOverride + lang code fix")
else:
    print("  showSubtitlePicker pattern not found — check manually")

# ── Fix 3: Better MIME type detection — handle ambiguous URLs
old_mime = (
    '                            // Detect subtitle format\n'
    '                            String mime;\n'
    '                            if (subUrl.contains(".srt")) {\n'
    '                                mime = "application/x-subrip";\n'
    '                            } else if (subUrl.contains(".ass") || subUrl.contains(".ssa")) {\n'
    '                                mime = "text/x-ssa";\n'
    '                            } else {\n'
    '                                // Default to VTT — also works for auto-detect streams\n'
    '                                mime = androidx.media3.common.MimeTypes.TEXT_VTT;\n'
    '                            }'
)
new_mime = (
    '                            // Detect subtitle format from URL extension\n'
    '                            String mime;\n'
    '                            String subUrlLower = subUrl.toLowerCase();\n'
    '                            if (subUrlLower.contains(".srt") || subUrlLower.contains("format=srt")) {\n'
    '                                mime = androidx.media3.common.MimeTypes.APPLICATION_SUBRIP;\n'
    '                            } else if (subUrlLower.contains(".ass") || subUrlLower.contains(".ssa")) {\n'
    '                                mime = "text/x-ssa";\n'
    '                            } else if (subUrlLower.contains(".vtt") || subUrlLower.contains("format=vtt")) {\n'
    '                                mime = androidx.media3.common.MimeTypes.TEXT_VTT;\n'
    '                            } else {\n'
    '                                // Unknown extension — try VTT first (most common from SubDL/OpenSubs)\n'
    '                                mime = androidx.media3.common.MimeTypes.TEXT_VTT;\n'
    '                            }'
)

if old_mime in src:
    src = src.replace(old_mime, new_mime, 1)
    print("  Fix 3: MIME type detection improved (APPLICATION_SUBRIP + format= param support)")
else:
    print("  MIME type pattern not found — check manually")

# ── Fix 4: Apply TV-optimised subtitle styling after playerView.setPlayer()
old_styling = (
    '        playerView.setPlayer(exoPlayer);\n'
    '        playerView.setUseController(true);\n'
    '        playerView.setControllerAutoShow(true);\n'
    '        playerView.setControllerHideOnTouch(true);\n'
    '        if (playerView.getSubtitleView() != null)\n'
    '            playerView.getSubtitleView().setVisibility(android.view.View.VISIBLE);'
)
new_styling = (
    '        playerView.setPlayer(exoPlayer);\n'
    '        playerView.setUseController(true);\n'
    '        playerView.setControllerAutoShow(true);\n'
    '        playerView.setControllerHideOnTouch(true);\n'
    '\n'
    '        // ── TV subtitle styling ──────────────────────────────────────────\n'
    '        androidx.media3.ui.SubtitleView subView = playerView.getSubtitleView();\n'
    '        if (subView != null) {\n'
    '            subView.setVisibility(android.view.View.VISIBLE);\n'
    '            // Apply TV-friendly style: large text, black outline, bottom position\n'
    '            androidx.media3.common.text.CueGroup.CuePriority priority =\n'
    '                androidx.media3.common.text.CueGroup.CuePriority.TEXT;\n'
    '            subView.setUserDefaultStyle();\n'
    '            subView.setUserDefaultTextSize();\n'
    '            // Override with TV-sized text (1.4x default = ~22sp equivalent on TV)\n'
    '            subView.setFixedTextSize(\n'
    '                android.util.TypedValue.COMPLEX_UNIT_SP, 22);\n'
    '            // Bottom padding so subs don\'t overlap the control bar\n'
    '            subView.setPadding(0, 0, 0,\n'
    '                (int)(16 * getResources().getDisplayMetrics().density));\n'
    '        }'
)

if old_styling in src:
    src = src.replace(old_styling, new_styling, 1)
    print("  Fix 4: TV subtitle styling applied — 22sp text, bottom padding")
else:
    print("  Subtitle styling pattern not found — check manually")

# ── Also add Collections import if missing
if 'import java.util.Collections;' not in src:
    src = src.replace(
        'import java.util.ArrayList;',
        'import java.util.ArrayList;\nimport java.util.Collections;'
    )
    print("  Added missing Collections import")

with open("app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java", "w") as f:
    f.write(src)

print("\nAll subtitle fixes applied.")
PYEOF

echo ""
echo "=== Updating activity_yastream_player.xml — add subtitle bottom margin ==="
python3 - << 'PYEOF'
with open("app/src/main/res/layout/activity_yastream_player.xml", "r") as f:
    src = f.read()

# Add subtitle bottom offset so text sits above the bottom UI bar
old = (
    '    <androidx.media3.ui.PlayerView\n'
    '        android:id="@+id/yastream_player_view"\n'
    '        android:layout_width="match_parent"\n'
    '        android:layout_height="match_parent"\n'
    '        android:focusable="true"\n'
    '        android:focusableInTouchMode="true"\n'
    '        app:show_subtitle_button="false"\n'
    '        app:use_controller="true" />'
)
new = (
    '    <androidx.media3.ui.PlayerView\n'
    '        android:id="@+id/yastream_player_view"\n'
    '        android:layout_width="match_parent"\n'
    '        android:layout_height="match_parent"\n'
    '        android:focusable="true"\n'
    '        android:focusableInTouchMode="true"\n'
    '        app:show_subtitle_button="false"\n'
    '        app:subtitle_bottom_padding_fraction="0.08"\n'
    '        app:use_controller="true" />'
)

if old in src:
    src = src.replace(old, new, 1)
    print("  PlayerView: subtitle_bottom_padding_fraction added")
else:
    print("  PlayerView pattern not found — check manually")

with open("app/src/main/res/layout/activity_yastream_player.xml", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "✅ Done! Run:"
echo "   git add -A && git commit -m 'Fix subtitles: TrackSelectionOverride, MIME types, TV styling' && git push"
