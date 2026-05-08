# ════════════════════════════════════
# Privacy Browser Pro — ProGuard Rules
# ════════════════════════════════════

# Keep all app classes
-keep class com.privacybrowser.** { *; }

# Keep annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable

# ── Android WebView ──
-keep class android.webkit.** { *; }
-dontwarn android.webkit.**

# ── SQLite ──
-keep class android.database.sqlite.** { *; }

# ── Reflection (used by ProxyManager) ──
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── Keep inner classes ──
-keepclassmembers class com.privacybrowser.browser.TabManager$Tab {
    public *;
}
-keepclassmembers class com.privacybrowser.browser.HistoryManager$HistoryEntry {
    public *;
}
-keepclassmembers class com.privacybrowser.browser.ProxyManager$ProxyConfig {
    public *;
}

# ── AppCompat ──
-keep class androidx.appcompat.** { *; }
-dontwarn androidx.**

# ── RecyclerView ──
-keep class androidx.recyclerview.** { *; }

# ── Remove logging in release ──
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ── Optimize ──
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose
