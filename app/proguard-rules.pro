# Muimex11 release keep rules
-keep class rikka.shizuku.** { *; }
-keep class com.muimex11.official.GameProfileStore { *; }
-keep class com.muimex11.official.LogicHelpers* { *; }
-keep class com.muimex11.official.ShizukuRunner { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-dontwarn com.google.mlkit.**
