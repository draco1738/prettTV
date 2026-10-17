#!/usr/bin/env bash
# Build (or reuse) the release APK and install it on the connected Android TV device.
# Includes RBTV+ by default. Select the device with TV=<ip>:5555 if more than one is connected.
#
#   ./install.sh            build + install
#   ./install.sh --no-build install the APK already in launcher/app/build/outputs/apk/release/
set -eu
cd "$(dirname "$0")"
NO_BUILD=false
LAUNCHER_ONLY=false
for arg in "$@"; do
  case "$arg" in
    --no-build) NO_BUILD=true ;;
    --launcher-only) LAUNCHER_ONLY=true ;;
    *) printf 'Unknown option: %s\n' "$arg" >&2; exit 2 ;;
  esac
done
source adb/common.sh
APK=launcher/app/build/outputs/apk/release/app-release.apk
if [ -f prettTV-0.1.8.apk ]; then APK=prettTV-0.1.8.apk; NO_BUILD=true; fi
if [ "$NO_BUILD" = false ]; then
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
COMPANION=""
if [ "$LAUNCHER_ONLY" = false ]; then
  if sh_ pm path com.rblive.app | grep -q '^package:'; then
    note "RBTV+ is already installed; keeping its version and data."
  else
    COMPANION="${RBTV_APK:-RBTV-3.0.320.apk}"
    if [ ! -f "$COMPANION" ]; then
      [ -z "${RBTV_APK:-}" ] || die "RBTV_APK does not exist: $RBTV_APK"
      mkdir -p results/companions
      COMPANION=results/companions/RBTV-3.0.320.apk
      if [ ! -f "$COMPANION" ]; then
        command -v curl >/dev/null || die "Use the complete release bundle, install curl, or set RBTV_APK to its APK."
        curl --fail --location --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 180 \
          https://github.com/draco1738/prettTV/releases/download/v0.1.8/RBTV-3.0.320.apk -o "$COMPANION.download" || die "RBTV+ download failed."
        mv -- "$COMPANION.download" "$COMPANION"
      fi
    fi
    expected=5e7f0b4eb571d4c662678cad8cd18254ec8abd55ead230d2f8e7ab684ff8f279
    if command -v sha256sum >/dev/null; then actual=$(sha256sum "$COMPANION" | awk '{print $1}');
    elif command -v shasum >/dev/null; then actual=$(shasum -a 256 "$COMPANION" | awk '{print $1}');
    else die "sha256sum or shasum is required to verify RBTV+."; fi
    [ "$actual" = "$expected" ] || die "RBTV+ checksum mismatch. No APK was installed."
  fi
fi
say "Installing $(du -h "$APK" | cut -f1) APK"
out=$("${A[@]}" install -r "$APK" 2>&1 </dev/null); echo "$out" | grep -q Success || die "install failed: $out"
audit "install" "$PKG $(sh_ dumpsys package $PKG | grep -m1 versionName | tr -d ' ')" "adb uninstall $PKG"
if [ -n "$COMPANION" ]; then
  say "Installing included RBTV+ 3.0.320"
  out=$("${A[@]}" install -r "$COMPANION" 2>&1 </dev/null) || die "prettTV installed, but RBTV+ installation failed: $out"
  echo "$out" | grep -q Success || die "prettTV installed, but RBTV+ installation failed: $out"
  audit "install" "com.rblive.app 3.0.320" "adb uninstall com.rblive.app"
fi
sh_ am start -n "$HOME_ACT" >/dev/null 2>&1; sleep 2
top=$(sh_ dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity')
echo "$top" | grep -q "$PKG" && note "prettTV is on screen." || note "WARNING: prettTV did not come to the foreground: $top"
note "Use Settings → Choose default Home app to select prettTV. The installer does not disable other launchers."
note "To remove it again: ./restore.sh"
