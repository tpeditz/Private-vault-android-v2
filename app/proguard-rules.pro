# Private Vault V2 Proguard / R8 Rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# Only retain the BouncyCastle Argon2id surface actually invoked by Private Vault;
# allow R8 to tree-shake unused BouncyCastle providers, X.509, PGP, and legacy ciphers.
-dontwarn org.bouncycastle.**
-keepclassmembers class org.bouncycastle.crypto.generators.Argon2BytesGenerator { *; }
-keepclassmembers class org.bouncycastle.crypto.params.Argon2Parameters { *; }
-keepclassmembers class org.bouncycastle.crypto.params.Argon2Parameters$Builder { *; }

# Keep ApkVerifier entry points while allowing R8 to remove unused apksig helpers.
-dontwarn com.android.apksig.**
-keepclassmembers class com.android.apksig.ApkVerifier { *; }
-keepclassmembers class com.android.apksig.ApkVerifier$Result { *; }

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
