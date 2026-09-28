#!/usr/bin/env bash
# Starts Shizuku over USB. Needed once after every reboot of the phone.
set -euo pipefail

# Shizuku 13.5+ starts from a native library in its install folder; older versions wrote start.sh.
adb shell '
  dir=$(dirname "$(pm path moe.shizuku.privileged.api | head -1 | sed "s/package://")")
  lib=$(ls "$dir"/lib/*/libshizuku.so 2>/dev/null | head -1)
  legacy=/storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
  if [ -n "$lib" ]; then "$lib"
  elif [ -f "$legacy" ]; then sh "$legacy"
  else echo "Shizuku not found. Install it and open it once."; exit 1
  fi
'
