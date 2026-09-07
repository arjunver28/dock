# Proguard rules for Shizuku
-keep class rikka.shizuku.** { *; }
-keepclassmembers class * implements android.os.IInterface {
    <fields>;
    <methods>;
}
