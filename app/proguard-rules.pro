# Muimex11 release keep rules.
# Only Shizuku's own classes need keeping (it binds/manages its Binder through
# reflection and AIDL stubs). The app's own code is reflection-free, so the old
# keeps for "LogicHelpers" (a class that never existed), ShizukuRunner and
# GameProfileStore were dead weight — their JSON is built field by field.
-keep class rikka.shizuku.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
