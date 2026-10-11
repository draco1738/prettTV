#!/usr/bin/env bash
# Build (or reuse) the release APK and install it on the connected Android TV device.
# Changes nothing else on the device. Select the device with TV=<ip>:5555 if more than one is connected.
#
#   ./install.sh            build + install
#   ./install.sh --no-build install the APK already in launcher/app/build/outputs/apk/release/
set -u
cd "$(dirname "$0")"
source adb/common.sh
APK=launcher/app/build/outputs/apk/release/app-release.apk
if [ "${1:-}" != "--no-build" ]; then
  # The Android Gradle Plugin needs JDK 17. Use JAVA_HOME if set, otherwise look for one.
  if [ -z "${JAVA_HOME:-}" ]; then
    for c in "$(/usr/libexec/java_home -v 17 2>/dev/null)" /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk; do
      [ -n "$c" ] && [ -x "$c/bin/java" ] && "$c/bin/java" -version 2>&1 | grep -q '"17' && { export JAVA_HOME=$c; break; }
    done
  fi
  say "Building release APK${JAVA_HOME:+ with JDK at $JAVA_HOME}"
  (cd launcher && ./gradlew -q assembleRelease) || die "build failed (JDK 17 and the Android SDK are required)"
fi
[ -f "$APK" ] || die "APK not found: $APK"
say "Device $TV: $(prop ro.product.manufacturer) $(prop ro.product.model), Android $(prop ro.build.version.release) (SDK $(prop ro.build.version.sdk))"
sh_ pm list features | grep -q android.software.leanback || die "Not an Android TV device (no leanback feature)."
say "Installing $(du -h "$APK" | cut -f1) APK"
out=$("${A[@]}" install -r "$APK" 2>&1 </dev/null); echo "$out" | grep -q Success || die "install failed: $out"
audit "install" "$PKG $(sh_ dumpsys package $PKG | grep -m1 versionName | tr -d ' ')" "adb uninstall $PKG"
sh_ am start -n "$HOME_ACT" >/dev/null 2>&1; sleep 2
top=$(sh_ dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity')
echo "$top" | grep -q "$PKG" && note "Rivi is on screen." || note "WARNING: Rivi did not come to the foreground: $top"
if sh_ settings get secure enabled_accessibility_services | grep -q "$PKG/"; then
  note "Home button: already opens Rivi."
else
  say "To make the Home button open Rivi, on the TV:"
  note "Settings → System → Accessibility → Rivi Home button → On"
fi
note "To remove it again: ./restore.sh"
