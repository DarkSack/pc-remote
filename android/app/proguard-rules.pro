# Keep BouncyCastle providers used at runtime for Ed25519.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.sack.pcremote.**$$serializer { *; }
-keepclassmembers class com.sack.pcremote.** {
    *** Companion;
}
-keepclasseswithmembers class com.sack.pcremote.** {
    kotlinx.serialization.KSerializer serializer(...);
}
