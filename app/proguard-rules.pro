# ──────────────────────────────────────────────────────────────────────────────
# Compass Duel — ProGuard / R8 Rules
# ──────────────────────────────────────────────────────────────────────────────

# ── Kotlin Serialization ─────────────────────────────────────────────────────
# Keep @Serializable classes and their generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.justb81.compassduel.**$$serializer { *; }
-keepclassmembers class com.justb81.compassduel.** {
    *** Companion;
}
-keepclasseswithmembers class com.justb81.compassduel.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Hilt / Dagger ────────────────────────────────────────────────────────────
-dontwarn dagger.hilt.android.internal.**

# ── Google Play Services (Nearby Connections) ────────────────────────────────
-dontwarn com.google.android.gms.**

# ── Coroutines ───────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── General ──────────────────────────────────────────────────────────────────
-keep class * extends android.app.Application
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
