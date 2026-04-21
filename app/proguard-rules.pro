# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# Glide
-keep public class * extends com.github.bumptech.glide.module.AppGlideModule
-keep public class * extends com.github.bumptech.glide.module.LibraryGlideModule
-keep class com.github.bumptech.glide.GeneratedAppGlideModuleImpl { *; }
-keep public enum com.github.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

# AndroidX WorkManager
-keep class androidx.work.Worker { *; }
-keep class androidx.work.impl.WorkerWrapper { *; }

# Project specific: Keep models and database entities
-keep class com.oriondev.moneywallet.model.** { *; }
-keep class com.oriondev.moneywallet.storage.database.Contract$** { *; }

# Keep workers
-keep class com.oriondev.moneywallet.worker.** { *; }

# MaterialDrawer
-keep class com.mikepenz.materialdrawer.** { *; }

# Zip4j
-keep class net.lingala.zip4j.** { *; }

# itextpdf
-keep class com.itextpdf.** { *; }
-dontwarn com.itextpdf.**

# Bouncy Castle (optional dependency for itextpdf)
-dontwarn org.bouncycastle.**

# OSMDroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Material Dialogs
-dontwarn com.afollestad.materialdialogs.**

# Apache Commons
-dontwarn org.apache.commons.**

# Keep common annotation pattern
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
