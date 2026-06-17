# ═══════════════════════════════════════════════════════════════════════════════
# PureNeroflix ProGuard / R8 Rules  — Anti-tamper + Performance
# ═══════════════════════════════════════════════════════════════════════════════

# ── Keep model classes ────────────────────────────────────────────────────────
-keep class com.neroflix.tv.app.models.** { *; }

# ── Keep OkHttp ──────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Keep Glide ────────────────────────────────────────────────────────────────
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

# ── Keep JSON ─────────────────────────────────────────────────────────────────
-keep class org.json.** { *; }

# ── Android TV / Leanback ────────────────────────────────────────────────────
-keep class androidx.leanback.** { *; }
-dontwarn androidx.leanback.**

# ── Keep Media3 / ExoPlayer ──────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# ── Keep Activation & License (critical — must not be renamed) ───────────────
-keep class com.neroflix.tv.app.LicenseManager { *; }
-keep class com.neroflix.tv.app.activities.ActivationActivity { *; }
-keep class com.neroflix.tv.app.activities.SplashActivity { *; }

# ── Aggressive obfuscation of all other classes ──────────────────────────────
-repackageclasses 'n'
-allowaccessmodification
-overloadaggressively

# ── Strip source file names and line numbers ──────────────────────────────────
-renamesourcefileattribute SourceFile
-keepattributes !SourceFile,!LineNumberTable

# ── Remove ALL logging in release ─────────────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# ── Aggressive optimization ───────────────────────────────────────────────────
-optimizationpasses 7
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!code/allocation/variable

# ── Anti-tamper: obfuscate security-critical methods ─────────────────────────
-keep,allowobfuscation class com.neroflix.tv.app.LicenseManager {
    private static boolean checkSignature(...);
    private static boolean checkPackageName(...);
    private static boolean isTampered(...);
}

# ── Prevent stack trace reconstruction ───────────────────────────────────────
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,EnclosingMethod

# ── Remove BuildConfig debug fields ──────────────────────────────────────────
-assumenosideeffects class com.neroflix.tv.app.BuildConfig {
    public static final boolean DEBUG return false;
}

# ── Protect string constants from easy extraction ────────────────────────────
-adaptclassstrings
-adaptresourcefilenames
-adaptresourcefilecontents

# ── Keep R classes ────────────────────────────────────────────────────────────
-keepclassmembers class **.R$* {
    public static <fields>;
}

# ── Keep custom views ─────────────────────────────────────────────────────────
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}

# ── Keep Activity/Service/Receiver/Provider ───────────────────────────────────
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ── Keep Parcelable ───────────────────────────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ── Keep native methods ───────────────────────────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Prevent enum obfuscation breaking ────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
