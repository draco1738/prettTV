#!/usr/bin/env bash
# Select a working replacement Home BEFORE uninstalling Luma.
# --stock-home explicitly restores the two Google TV Home packages used in the TCL setup.
set -u
cd "$(dirname "$0")"
source adb/common.sh
if [ "${1:-}" = "--stock-home" ]; then
  for package in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith; do
    if sh_ pm path "$package" | grep -q package:; then
      sh_ pm enable --user 0 "$package" >/dev/null || die "Could not enable $package; Luma remains installed"
      audit "enable" "$package" ""
    fi
  done
  replacement=$(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME -p com.google.android.apps.tv.launcherx | tail -n 1)
else
  replacement=$(home_candidates | grep '/' | grep -v "$PKG/" | grep -v FallbackHome | head -n 1 | xargs)
fi
case "$replacement" in
  */*) ;;
  *) die "No replacement Home is enabled. Restore one first, or use ./restore.sh --stock-home. Luma remains installed." ;;
esac
sh_ cmd package set-home-activity --user 0 "$replacement" >/dev/null || die "Home selection failed; Luma remains installed"
[ "$(current_home)" = "$replacement" ] || die "Replacement Home did not become the resolver; Luma remains installed"
sh_ am start -n "$replacement" >/dev/null || die "Replacement Home did not launch; Luma remains installed"
if sh_ pm path "$PKG" | grep -q package:; then
  "${A[@]}" uninstall "$PKG" </dev/null || die "Uninstall failed"
  audit "uninstall" "$PKG" ""
fi
say "Home restored to $replacement"
note "This does not reset DNS, re-enable other debloated apps, or change streaming accounts."