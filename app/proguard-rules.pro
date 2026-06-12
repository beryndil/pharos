# Strip PHI-capable verbose/debug/info logs from release (security launch-gate).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static boolean isLoggable(java.lang.String, int);
}
# Keep BroadcastReceivers (alarm/boot) — R8 must not strip alarm delivery.
-keep class * extends android.content.BroadcastReceiver
# kotlinx.serialization
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <fields>; }

# Room — keep all entity/DAO/database classes so R8 doesn't strip Room-generated code.
-keep class com.beryndil.pharos.data.** { *; }
-keepclassmembers class com.beryndil.pharos.data.** { *; }

# SQLCipher — native JNI bridge must not be stripped.
-keep class net.zetetic.database.** { *; }

# Tink — keep all Tink classes (registered primitives, key managers, proto-based serialization).
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }
