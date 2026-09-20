#!/usr/bin/env bash
set -euo pipefail
root="${MVS_ROOT:-$HOME/mvs-demo}"
namespace="${MVS_NAMESPACE:-mvs-smoke}"
exec 9>"$root/guest.lock"
flock -n 9 || { echo "Guest lock is held" >&2; exit 1; }
if ! sudo -n ip netns list | awk '{print $1}' | rg -qx "$namespace"; then
  sudo -n ip netns add "$namespace"
fi
test "$(sudo -n ip netns exec "$namespace" ip -o link show | wc -l)" -eq 1
sudo -n ip netns exec "$namespace" ip link set lo up
if sudo -n ip netns exec "$namespace" ss -ltnH | rg -q ':8038 '; then
  echo "An emulator already owns namespace port 8038" >&2
  exit 1
fi
cd "$root/mvs-tk5"
exec sudo -n ip netns exec "$namespace" runuser -u "$(id -un)" -- \
  env HERCULES_RC=scripts/ipl.rc TK5CONS=intcons REP101A=specific CMD101A=02 \
  "$root/hercules-install/bin/hercules" -d -f conf/tk5.cnf \
  >>"$root/evidence/console.log" 2>&1
