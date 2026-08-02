# قواعد التصغير — نحافظ على ما تحتاجه Room و Glide و Media3
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault

-keep class com.deeprecovery.pro.data.db.** { *; }
-keep class com.deeprecovery.pro.data.model.** { *; }

-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { **[] $VALUES; public *; }

-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
