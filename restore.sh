#!/usr/bin/env bash
# Remove Rivi. Because it changes nothing else on the device, this is the whole restore:
# the Home-button service disappears with the app and Home opens the stock launcher again.
# No factory reset, no app data touched.
set -u
cd "$(dirname "$0")"
source adb/common.sh
say "Removing Rivi from $TV"
if sh_ pm path "$PKG" | grep -q package:; then
  "${A[@]}" uninstall "$PKG" </dev/null >/dev/null 2>&1 && { note "uninstalled $PKG"; audit "uninstall" "$PKG" ""; } || die "uninstall failed"
else
  note "$PKG is not installed"
fi
sh_ input keyevent KEYCODE_HOME >/dev/null; sleep 2
say "Home app: $(current_home)"
dis=$(sh_ pm list packages -d | tr '\n' ' ')
if [ -n "$dis" ]; then
  note "Disabled packages on this device (not disabled by Rivi): $dis"
  note "If another guide had you disable the Google launcher, re-enable it with:"
  note "  adb shell pm enable --user 0 com.google.android.apps.tv.launcherx"
  note "  adb shell pm enable --user 0 com.google.android.tungsten.setupwraith"
else
  note "No disabled packages."
fi
cat <<'TXT'

Without a computer: Settings → Apps → Rivi → Uninstall.
TXT
