#!/bin/bash
# symbols-check.sh: no duplicate top-level struct|class|enum|actor|protocol name across APP/Sources, across APP/Tests,
# or across PKG/Sources/STMPTestSupport (D108). Nested types are indented and never matched.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd); cd "$ROOT"
rc=0
scan() { # label dir...
  local label="$1"; shift
  local out; out=$(find "$@" -name '*.swift' -not -path '*/__Snapshots__/*' 2>/dev/null | sort | while read -r f; do
    grep -E '^(public |internal |package |private |fileprivate |final |nonisolated |open )*(struct|class|enum|actor|protocol) +[A-Za-z_][A-Za-z0-9_]*' "$f" |
      grep -Ev '^(private|fileprivate) ' |
      sed -E 's/^(public |internal |package |final |nonisolated |open )*(struct|class|enum|actor|protocol) +([A-Za-z_][A-Za-z0-9_]*).*/\3/' | sed "s|\$|\t$f|"
  done || true)
  local dups; dups=$(printf '%s\n' "$out" | awk -F'\t' 'NF==2{c[$1]++; f[$1]=f[$1]" "$2} END{for(k in c) if(c[k]>1) print k":"f[k]}')
  [ -z "$dups" ] || { echo "symbols-check: duplicate top-level names in $label:"; echo "$dups"; rc=1; }
}
scan APP/Sources ios/App/Sources
scan APP/Tests ios/App/Tests
scan STMPTestSupport ios/STMPKit/Sources/STMPTestSupport
[ $rc = 0 ] && echo "symbols-check: ok" || true
exit $rc
