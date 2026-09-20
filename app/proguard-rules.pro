# Keep all app and hook classes
-keep class com.nfchider.** { *; }

# Keep libxposed classes and interfaces
-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**

# Keep osmdroid classes
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
