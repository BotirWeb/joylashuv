# ===== FIREBASE =====
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ===== HILT =====
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.hilt.**

# ===== ROOM =====
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# ===== SERVICE VA RECEIVER'LAR =====
-keep class com.android.system.core.location.LocationForegroundService { *; }
-keep class com.android.system.core.BootReceiver { *; }
-keep class com.android.system.core.ScreenOnReceiver { *; }
-keep class com.android.system.core.BootCheckWorker { *; }

# ===== MODELS =====
-keep class com.android.system.core.location.models.** { *; }

# ===== WORKMANAGER =====
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# ===== KOTLIN =====
-keep class kotlin.** { *; }
-dontwarn kotlin.**
-keepattributes *Annotation*
-keepattributes Signature

# ===== LOG O'CHIRISH (release) =====
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int i(...);
    public static int v(...);
    public static int w(...);
    public static int e(...);
}