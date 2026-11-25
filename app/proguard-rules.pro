# Private Vault V2 Proguard Rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }
-dontwarn com.android.apksig.**
-keep class com.android.apksig.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.google.android.gms.auth.** { *; }
