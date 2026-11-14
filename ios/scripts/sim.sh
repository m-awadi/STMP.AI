#!/bin/bash
# sim.sh ensure NAME | boot UDID | shutdown UDID | delete NAME | udid NAME   (simulator pinned by sim.env, D61)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); . "$HERE/sim.env"
udid_of() { xcrun simctl list devices -j | jq -r --arg n "$1" '[.devices[][]|select(.name==$n and (.isAvailable // true))]|first|.udid // empty'; }
case "${1:-}" in
  udid) udid_of "$2";;
  ensure)
    xcrun simctl list runtimes | grep -q "$STMP_SIM_RUNTIME" || { echo "sim: runtime $STMP_SIM_RUNTIME is not installed (never downloaded by this script)" >&2; exit 3; }
    U=$(udid_of "$2"); [ -n "$U" ] || U=$(xcrun simctl create "$2" "$STMP_SIM_DEVICE_TYPE" "$STMP_SIM_RUNTIME")
    xcrun simctl bootstatus "$U" -b >/dev/null 2>&1 || true
    echo "$U";;
  shutdown) xcrun simctl shutdown "$2" 2>/dev/null || true;;
  delete) U=$(udid_of "$2"); [ -z "$U" ] || { xcrun simctl shutdown "$U" 2>/dev/null || true; xcrun simctl delete "$U"; };;
  *) echo "usage: sim.sh ensure|udid|shutdown|delete" >&2; exit 2;;
esac
