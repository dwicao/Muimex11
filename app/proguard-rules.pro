# Muimex11 release keep rules.
# Only Shizuku's own classes need keeping (it binds/manages its Binder through
# reflection and AIDL stubs). The app's own code is reflection-free, so the old
# keeps for "LogicHelpers" (a class that never existed), ShizukuRunner and
# GameProfileStore were dead weight — their JSON is built field by field.
-keep class rikka.shizuku.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod

# Shizuku instantiates the DeX Hz display service by class name in its own process,
# calling the (Context) constructor; the AIDL stub and proxy cross that boundary.
-keep class com.muimex11.official.DexHzService { public <init>(android.content.Context); *; }
-keep class com.muimex11.official.IDexHzService { *; }
-keep class com.muimex11.official.IDexHzService$* { *; }
-keep class com.muimex11.official.DexDisplayControl { *; }
-keep class com.muimex11.official.DexDisplayControl$* { *; }
-keep class com.muimex11.official.DexRefreshSession { *; }
-keep class com.muimex11.official.DexRefreshSession$* { *; }
-keep class com.muimex11.official.DexSurfaceFlingerCodes { *; }
-keep class com.muimex11.official.DexSurfaceFlingerCodes$* { *; }
