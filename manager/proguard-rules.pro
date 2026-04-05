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

# ===== GOOGLE MAPS =====
-keep class com.google.maps.** { *; }
-dontwarn com.google.maps.**

# ===== ACTIVITY'LAR =====
-keep class com.android.system.manager.AdminAuthActivity { *; }
-keep class com.android.system.manager.AdminDashboardActivity { *; }

# ===== MODELS =====
-keep class com.android.system.manager.**.models.** { *; }

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

# ===== FIREBASE DATA MODELS =====
-keepclassmembers class com.android.system.manager.AdminDashboardActivity$DeviceData {
    <fields>;
    <init>(...);
}

-keepclassmembers class * {
    @com.google.firebase.database.PropertyName <fields>;
    @com.google.firebase.database.PropertyName <methods>;
}