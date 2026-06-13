# NeroFlix ProGuard Rules

# ─── Keep model classes ───────────────────────────────────────────────────────
-keep class com.neroflix.tv.app.models.** { *; }

# ─── Keep OkHttp ─────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# ─── Keep Glide ──────────────────────────────────────────────────────────────
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }

# ─── Keep JSON ───────────────────────────────────────────────────────────────
-keep class org.json.** { *; }

# ─── Android TV / Leanback ───────────────────────────────────────────────────
-keep class androidx.leanback.** { *; }

# ─── Keep Media3 / ExoPlayer ─────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ─── Aggressive obfuscation ──────────────────────────────────────────────────
-repackageclasses 'x'
-allowaccessmodification
-overloadaggressively

# ─── Strip source file names and line numbers from stack traces ───────────────
-renamesourcefileattribute x
-keepattributes !SourceFile,!LineNumberTable

# ─── Remove logging (strips all Log.d/Log.e/Log.w calls from release) ─────────
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int e(...);
    public static int w(...);
    public static int i(...);
    public static int v(...);
    public static boolean isLoggable(...);
}

# ─── Optimize aggressively ───────────────────────────────────────────────────
-optimizationpasses 7
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!code/allocation/variable

# ─── Prevent reflection-based attacks on security methods ────────────────────
-keep,allowobfuscation class com.neroflix.tv.app.activities.MainActivity {
    private boolean a();
    private boolean b();
    private boolean c();
    private boolean d();
    private boolean e();
}
