-keepclassmembers class **$$serializer { *; }
-keepattributes *Annotation*, InnerClasses
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
# Media3 touches newer platform classes behind SDK_INT checks; never fail the build on them.
-dontwarn android.media.metrics.**
