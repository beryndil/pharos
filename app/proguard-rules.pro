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
