#!/usr/bin/env bash
# Phase A: collect a machine-readable device profile (no serials, no account data).
# Usage: adb/profile.sh  -> results/device-profile.json (+ raw dumps in results/profile-raw/)
source "$(dirname "$0")/common.sh"
RAW=$ROOT/results/profile-raw; mkdir -p "$RAW"
OUT=$ROOT/results/device-profile.json

say "Profiling $TV"
j() { python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"; }
num() { tr -dc '0-9' <<<"$1"; }

MANUF=$(prop ro.product.manufacturer); BRAND=$(prop ro.product.brand); MODEL=$(prop ro.product.model)
DEVICE=$(prop ro.product.device); NAME=$(prop ro.product.name); BOARD=$(prop ro.board.platform)
HW=$(prop ro.hardware); CHIP=$(prop ro.soc.model); SOCM=$(prop ro.soc.manufacturer)
REL=$(prop ro.build.version.release); SDK=$(prop ro.build.version.sdk); FP=$(prop ro.build.fingerprint)
INC=$(prop ro.build.version.incremental); DISP=$(prop ro.build.display.id); SEC=$(prop ro.build.version.security_patch)
ABI=$(prop ro.product.cpu.abilist); ABI1=$(prop ro.product.cpu.abi); DENS=$(prop ro.sf.lcd_density)
TVTYPE=$(sh_ pm list features | grep -c android.software.leanback)

sh_ cat /proc/cpuinfo > "$RAW/cpuinfo.txt"
sh_ cat /proc/meminfo > "$RAW/meminfo.txt"
sh_ 'for c in /sys/devices/system/cpu/cpu[0-9]*; do echo "$c $(cat $c/cpufreq/cpuinfo_min_freq 2>/dev/null) $(cat $c/cpufreq/cpuinfo_max_freq 2>/dev/null) $(cat $c/cpufreq/scaling_cur_freq 2>/dev/null) $(cat $c/cpufreq/scaling_governor 2>/dev/null)"; done' > "$RAW/cpufreq.txt"
sh_ 'for t in /sys/class/thermal/thermal_zone*; do echo "$t $(cat $t/type 2>/dev/null) $(cat $t/temp 2>/dev/null)"; done' > "$RAW/thermal.txt"
sh_ dumpsys display > "$RAW/display.txt"
sh_ dumpsys SurfaceFlinger > "$RAW/surfaceflinger.txt" 2>/dev/null
sh_ wm size > "$RAW/wm_size.txt"; sh_ wm density >> "$RAW/wm_size.txt"
sh_ df -h /data > "$RAW/df.txt"
sh_ dumpsys meminfo > "$RAW/dumpsys_meminfo.txt"
sh_ pm list packages -f > "$RAW/packages.txt"
sh_ pm list packages -d > "$RAW/packages_disabled.txt"
sh_ pm list features > "$RAW/features.txt"
home_candidates > "$RAW/home_candidates.txt"
sh_ settings list global > "$RAW/settings_global.txt"
sh_ settings list system > "$RAW/settings_system.txt"
sh_ settings list secure | grep -v -i -E "account|token|android_id|bluetooth_address|wifi_mac" > "$RAW/settings_secure.txt"
sh_ getprop | grep -v -i -E "serial|imei|mac|ssid|uuid|token|secret" > "$RAW/getprop.txt"
sh_ dumpsys package com.google.android.apps.tv.launcherx 2>/dev/null | grep -E "versionName|versionCode|enabled=" | head -5 > "$RAW/launcherx.txt"
sh_ ps -A -o PID,RSS,VSZ,NAME > "$RAW/ps.txt"
sh_ dumpsys activity activities 2>/dev/null | grep -E "mResumedActivity|topResumedActivity" | head -3 > "$RAW/top_activity.txt"
sh_ 'cat /proc/sys/vm/swappiness; cat /proc/swaps' > "$RAW/swap.txt"
sh_ 'cat /sys/block/zram0/disksize 2>/dev/null; cat /sys/block/zram0/mm_stat 2>/dev/null' > "$RAW/zram.txt"
sh_ 'cat /proc/pressure/memory 2>/dev/null' > "$RAW/psi_memory.txt"

MEMTOTAL=$(awk '/MemTotal/{print $2}' "$RAW/meminfo.txt"); MEMAVAIL=$(awk '/MemAvailable/{print $2}' "$RAW/meminfo.txt")
SWAPTOTAL=$(awk '/SwapTotal/{print $2}' "$RAW/meminfo.txt"); SWAPFREE=$(awk '/SwapFree/{print $2}' "$RAW/meminfo.txt")
NCPU=$(grep -c ^processor "$RAW/cpuinfo.txt"); CPUPART=$(grep -m1 "CPU part" "$RAW/cpuinfo.txt" | awk '{print $NF}')
CPUMAX=$(awk '{print $3}' "$RAW/cpufreq.txt" | sort -n | tail -1); CPUMIN=$(awk '{print $2}' "$RAW/cpufreq.txt" | sort -n | head -1)
GOV=$(awk 'NR==1{print $5}' "$RAW/cpufreq.txt")
PHYS=$(grep -m1 "Physical size" "$RAW/wm_size.txt" | awk '{print $3}'); OVR=$(grep -m1 "Override size" "$RAW/wm_size.txt" | awk '{print $3}')
WMDENS=$(grep -m1 "Physical density" "$RAW/wm_size.txt" | awk '{print $3}')
REFRESH=$(grep -o -m1 "fps=[0-9.]*" "$RAW/display.txt" | head -1 | cut -d= -f2)
GPU=$(grep -m1 -i "GLES:" "$RAW/surfaceflinger.txt" | sed 's/^ *//')
DF=$(tail -1 "$RAW/df.txt")
HOME_NOW=$(current_home)
STREAM=$(grep -o -E "com\.(netflix\.ninja|amazon\.amazonvideo\.livingroom|google\.android\.youtube\.tv|apple\.atve\.androidtv\.appletv|disney\.disneyplus|hbo\.hbonow|wbd\.stream|spotify\.tv\.android|plexapp\.android|google\.android\.videos|max\.|viaplay\.android|yle\.webtv|mtv\.areena|telia\.|dna\.)[a-z.]*" "$RAW/packages.txt" | sort -u | paste -sd, -)
JMGO=$(grep -i -o -E "package:[^=]*=(com\.jmgo[^ ]*|com\.jmgo\.[^ ]*)" "$RAW/packages.txt" | sed 's/.*=//' | sort -u | paste -sd, -)
JMGO_ALL=$(grep -o -E "=[a-zA-Z0-9_.]*jmgo[a-zA-Z0-9_.]*" "$RAW/packages.txt" | tr -d = | sort -u | paste -sd, -)
PERMS="shell"; sh_ 'id' > "$RAW/id.txt"

python3 - "$OUT" <<PY
import json,sys
d = {
 "collected_at": "$(now_iso)",
 "manufacturer": "$MANUF", "brand": "$BRAND", "model": "$MODEL", "codename": "$DEVICE", "product": "$NAME",
 "board_platform": "$BOARD", "hardware": "$HW", "soc": {"model": "$CHIP", "manufacturer": "$SOCM"},
 "android": {"release": "$REL", "sdk": int("${SDK:-0}" or 0), "fingerprint": "$FP", "incremental": "$INC", "display_id": "$DISP", "security_patch": "$SEC"},
 "cpu": {"abilist": "$ABI", "primary_abi": "$ABI1", "cores": int("${NCPU:-0}" or 0), "arm_part": "$CPUPART", "min_khz": "$CPUMIN", "max_khz": "$CPUMAX", "governor": "$GOV"},
 "memory_kb": {"total": int("${MEMTOTAL:-0}" or 0), "available_now": int("${MEMAVAIL:-0}" or 0), "swap_total": int("${SWAPTOTAL:-0}" or 0), "swap_free": int("${SWAPFREE:-0}" or 0)},
 "display": {"physical": "$PHYS", "override": "$OVR", "density": "${WMDENS}", "refresh_hz": "$REFRESH", "gpu": "$GPU"},
 "storage_data": "$DF",
 "is_android_tv": $([ "$TVTYPE" -ge 1 ] && echo True || echo False),
 "home_activity": "$HOME_NOW",
 "streaming_packages": "$STREAM".split(",") if "$STREAM" else [],
 "jmgo_packages": [p for p in "$JMGO_ALL".split(",") if p],
 "adb_identity": open("$RAW/id.txt").read().strip(),
}
json.dump(d, open(sys.argv[1], "w"), indent=2)
print(json.dumps(d, indent=2))
PY
say "Saved $OUT (raw dumps in $RAW)"
