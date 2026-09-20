#!/usr/bin/env bash
set -euo pipefail
root="${MVS_ROOT:-$HOME/mvs-demo}"
out="${1:?Usage: tk5_macros.sh OUTPUT_DIRECTORY}"
mkdir -p "$out/ascii" "$out/ebcdic"
unzip -p "$root/downloads/mvs-tk5.zip" mvs-tk5/dasd/tk5res.390 \
  >"$out/pristine-tk5res.390"
"$root/hercules-install/bin/dasdpdsu" "$out/pristine-tk5res.390" \
  SYS1.MACLIB ASCII "$out/ascii"
"$root/hercules-install/bin/dasdpdsu" "$out/pristine-tk5res.390" \
  SYS1.MACLIB "$out/ebcdic"
