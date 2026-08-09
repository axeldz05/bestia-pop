# BestiaPop release R8 — mapping goes in the AAB for Play / Crashlytics.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile
-keep public class * extends java.lang.Exception

# App code: shrink unused deps, don't rename our types (Room entities, Ktor routes, VM).
-keep class com.bestiapop.android.** { *; }

-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

-keep class io.ktor.** { *; }
-keep class io.netty.** { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn io.ktor.**
-dontwarn io.netty.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.serialization.**

-keepclasseswithmembernames class * {
    native <methods>;
}
