# Compose, Room, Hilt, WorkManager, Glance and Health Connect ship their own consumer rules.

# Glance stores each widget's layout state with its own shaded protobuf-lite, which reads message fields
# by reflection. R8's full mode renames or strips those fields in a release build, and the launcher then
# shows "Can't load widget". Debug builds aren't shrunk, which is why the emulator widget test passed.
-keep class androidx.glance.appwidget.protobuf.** { *; }
-keep class androidx.glance.appwidget.proto.** { *; }
-keepclassmembers class * extends androidx.glance.appwidget.protobuf.GeneratedMessageLite { <fields>; }
