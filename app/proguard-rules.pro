# Disable obfuscation (keep shrinking and optimization)
-dontobfuscate

# Keep source file names and line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class me.fleey.futon.**$$serializer { *; }
-keepclassmembers class me.fleey.futon.** {
    *** Companion;
}
-keepclasseswithmembers class me.fleey.futon.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**

# Netty (Ktor dependency)
-dontwarn io.netty.**
-dontwarn io.netty.internal.tcnative.**
-dontwarn jdk.jfr.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn reactor.blockhound.**

# Google Tink (security-crypto dependency)
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-keep class com.google.crypto.tink.** { *; }

# Koin
-keepclassmembers class * { public <init>(...); }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# AIDL interfaces
-keep class me.fleey.futon.**.I* { *; }
-keep class * extends android.os.Binder { *; }
