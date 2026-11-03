# ---- R8 / ProGuard rules for a release build ----------------------------------
# R8 full mode is enabled by AGP 8.x. These libraries use reflection or
# ServiceLoader discovery and must be kept conservatively.

# JavaMail / activation (provider lookup and DataHandler are reflection based).
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-dontwarn java.awt.**
-dontwarn java.beans.Beans
-dontwarn javax.security.sasl.**

# PDFBox Android uses reflection for its resource loader on some devices.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.hyphenation.** { *; }
-dontwarn org.apache.fontbox.**
-dontwarn com.tom_roush.pdfbox.**

# Room generated code + our entities/data classes (fields are read by name in
# generated adapters; keeping this package avoids subtle release-only crashes).
-keep class ir.salisec.autosender.data.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# OkHttp ships its own consumer rules; silence optional-platform warnings only.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin / coroutines metadata used by the Compose compiler and serialization.
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# Keep any custom Application / Service reachable from the manifest.
-keep class ir.salisec.autosender.App { *; }
-keep class ir.salisec.autosender.send.SendService { *; }
