#!/usr/bin/env bash
# Runs inside the emulator job: smoke-tests the release APK the way a person would (install,
# open, no crash), then runs the screen tests at one of the Fold's screen sizes.
set -uo pipefail
apk="$1"
results="$2"
screen="$3"     # cover-screen or inner-screen
pkg=com.pandyabhavik.pedometer
mkdir -p "$results"
status=0

adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
sleep 5

if [ "$screen" = cover-screen ]; then
  adb shell wm size 1080x2520
  adb shell wm density 420
  # Larger text, as many people use on the small screen: nothing should be cut off.
  adb shell settings put system font_scale 1.15
else
  adb shell wm size 1968x2184
  adb shell wm density 374
fi
sleep 3

echo "== Smoke test of the release APK =="
adb logcat -c
adb install -r "$apk"
for p in ACTIVITY_RECOGNITION POST_NOTIFICATIONS; do adb shell pm grant $pkg android.permission.$p || true; done
adb shell am start -W -n $pkg/.MainActivity
sleep 8
adb exec-out screencap -p > "$results/release-1-first-run.png"
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell am start -W -n $pkg/.MainActivity
sleep 4
adb exec-out screencap -p > "$results/release-2-reopened.png"
adb logcat -d -b crash > "$results/crash-release.txt"
if grep -q "$pkg" "$results/crash-release.txt"; then
  echo "::error::The release APK crashed"
  cat "$results/crash-release.txt"
  status=1
elif ! adb shell pidof $pkg >/dev/null; then
  echo "::error::The release APK isn't running after launch"
  status=1
else
  echo "Release APK: opens and stays open"
fi
adb logcat -d > "$results/logcat-release.txt"
adb uninstall $pkg >/dev/null || true

echo "== Screen tests ($screen) =="
adb logcat -c
adb shell rm -rf /sdcard/Download/pedometer-screens
if ! ./gradlew --no-daemon connectedDebugAndroidTest; then status=1; fi
mkdir -p "$results/$screen"
adb pull /sdcard/Download/pedometer-screens/. "$results/$screen/" >/dev/null || true
cp -r app/build/reports/androidTests/connected "$results/$screen/report" 2>/dev/null || true
adb logcat -d > "$results/$screen/logcat.txt"
adb logcat -d -b crash > "$results/$screen/crash.txt"
if grep -q "$pkg" "$results/$screen/crash.txt"; then
  echo "::error::The app crashed during the screen tests"
  status=1
fi

adb shell wm size reset
adb shell wm density reset
exit $status
