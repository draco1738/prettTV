#!/usr/bin/env bash
# Shared helpers for Rivi adb scripts. Source this file.
# Device is chosen by $TV (serial as shown in `adb devices`, e.g. 192.168.1.50:5555),
# falling back to the single connected device.
set -u
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PKG=dev.zane.lumatv
HOME_ACT=$PKG/fi.jommi.rivi.MainActivity
AUDIT=$ROOT/results/audit.jsonl

pick_device() {
  if [ -n "${TV:-}" ]; then echo "$TV"; return; fi
  local devs; devs=$(adb devices | awk 'NR>1 && $2=="device"{print $1}')
  local n; n=$(echo "$devs" | grep -c . || true)
  if [ "$n" -eq 1 ]; then echo "$devs"; return; fi
  echo "ERROR: set TV=<serial> (adb devices lists $n devices)" >&2; exit 1
}
TV=$(pick_device)
A=(adb -s "$TV")
# adb shell forwards stdin; always give it /dev/null. Strip CRs.
sh_() { "${A[@]}" shell "$@" </dev/null | tr -d '\r'; }
prop() { sh_ getprop "$1"; }
say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }
die() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }
# audit "action" "detail" "undo-command"  -> appends one JSON line to results/audit.jsonl
# (a local log of what these scripts did to the device; results/ is git-ignored)
audit() {
  mkdir -p "$(dirname "$AUDIT")"
  printf '{"ts":"%s","device":"%s","action":"%s","detail":"%s","undo":"%s"}\n' "$(now_iso)" "$TV" "$1" "$2" "${3:-}" >> "$AUDIT"
}
current_home() { sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1; }
home_candidates() { sh_ cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME; }
