#!/bin/bash
# ownership-check.sh --ticket Tn|T0|A<n>|P<n> [--tsv FILE] [--files FILE]   |   --self-test
# Asserts every changed path is owned by the ticket (PLAN 3 step 3, D76, D139).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
TSV="$HERE/ownership.tsv"; TICKET=""; FILES_FROM=""; SELF=0
while [ $# -gt 0 ]; do case "$1" in
  --ticket) TICKET="$2"; shift 2;; --tsv) TSV="$2"; shift 2;; --files) FILES_FROM="$2"; shift 2;;
  --self-test) SELF=1; shift;; *) echo "ownership-check: unknown arg $1" >&2; exit 2;; esac; done

rows() { grep -v '^#' "$TSV" | grep -v '^$' || true; }                     # ticket<TAB>prefix
prefixes_of() { rows | awk -F'\t' -v t="$1" '$1==t && $2 !~ /^@/ {print $2}'; }
matches() { # path prefix  (prefix ending in / is a directory prefix, otherwise an exact file)
  case "$2" in */) [[ "$1" == "$2"* ]];; *) [[ "$1" == "$2" ]];; esac; }

# True when no prefix of one non-A/P ticket is a prefix of another ticket's (D139: A and P rows exempt).
no_overlap() {
  local tsv="$1" bad=0 a b ta tb pa pb
  local list; list=$(grep -v '^#' "$tsv" | awk -F'\t' 'NF==2 && $1 !~ /^[AP][0-9]+$/ && $2 !~ /^@/ {print $1"\t"$2}')
  while IFS=$'\t' read -r ta pa; do
    while IFS=$'\t' read -r tb pb; do
      [ "$ta" = "$tb" ] && continue
      case "$pa" in */) [[ "$pb" == "$pa"* ]] && { echo "overlap: $ta $pa  vs  $tb $pb"; bad=1; };; esac
      [ "$pa" = "$pb" ] && { echo "overlap: $ta $pa == $tb $pb"; bad=1; }
    done <<< "$list"
  done <<< "$list"
  return $bad
}

# Check path list against ticket. Prints offenders. Args: ticket, then paths on stdin.
check_paths() {
  local t="$1" ok=0 p q allowed
  case "$t" in
    T0) allowed=$( { rows | awk -F'\t' '$2 !~ /^@/ {print $2}'; } ) ;;
    *)  allowed=$(prefixes_of "$t"; [[ "$t" =~ ^[TAP][0-9]+$ ]] && echo "ios/changes/$t.md" || true) ;;
  esac
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    local hit=0
    while IFS= read -r q; do [ -z "$q" ] && continue; matches "$p" "$q" && { hit=1; break; }; done <<< "$allowed"
    [ $hit = 1 ] || { echo "not owned by $t: $p"; ok=1; }
  done
  return $ok
}

base_merged() { # ticket-list, RUN-STATE json at $STMP_RUN_STATE
  local f="${STMP_RUN_STATE:-}" t st
  [ -n "$f" ] && [ -f "$f" ] || { echo "P ticket refused: STMP_RUN_STATE is not a file"; return 1; }
  for t in ${1//,/ }; do
    st=$(jq -r --arg t "$t" '((.tickets // .)[$t]) as $x | if ($x|type)=="object" then ($x.status // $x.state // "") else ($x // "") end' "$f" 2>/dev/null || echo "")
    [ "$st" = "merged" ] || { echo "P ticket refused: base ticket $t is '$st', not merged"; return 1; }
  done
}

if [ $SELF = 1 ]; then
  fail=0; T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
  expect() { local name="$1" want="$2" got="$3"; [ "$want" = "$got" ] || { echo "SELF-TEST FAIL: $name (want $want got $got)"; fail=1; }; }
  # 1. The real table has no overlap.
  no_overlap "$HERE/ownership.tsv" >/dev/null; expect "real table no-overlap" 0 $?
  # 2. An overlapping table is caught; A and P rows are exempt.
  printf 'T1\tios/x/\nT2\tios/x/y/\n' > "$T/o.tsv"; no_overlap "$T/o.tsv" >/dev/null && r=0 || r=1; expect "overlap detected" 1 $r
  printf 'T1\tios/x/\nA1\tios/x/y/\nP1\t@base=T1\nP1\tios/x/z.swift\n' > "$T/e.tsv"; no_overlap "$T/e.tsv" >/dev/null && r=0 || r=1; expect "A/P rows exempt" 0 $r
  # 3. Ticket mode: owned file passes, foreign file and a prefix-sibling fail.
  TSV="$HERE/ownership.tsv"
  printf 'ios/STMPKit/Sources/STMPHTTP/Server.swift\nios/changes/T1.md\n' | check_paths T1 >/dev/null && r=0 || r=1; expect "T1 owned" 0 $r
  printf 'ios/STMPKit/Sources/STMPCore/X.swift\n' | check_paths T1 >/dev/null && r=0 || r=1; expect "T1 foreign refused" 1 $r
  printf 'ios/STMPKit/Sources/STMPHTTPX/a.swift\n' | check_paths T1 >/dev/null && r=0 || r=1; expect "prefix needs the slash" 1 $r
  # 4. T0 mode: any row's prefix ok, unknown path refused.
  printf 'ios/STMPKit/Sources/STMPHTTP/a.swift\nios/CHANGELOG.md\n' | check_paths T0 >/dev/null && r=0 || r=1; expect "T0 any row" 0 $r
  printf 'app/build.gradle.kts\n' | check_paths T0 >/dev/null && r=0 || r=1; expect "T0 unowned refused" 1 $r
  # 5. One A-row case: exactly its paths.
  printf 'A1\tios/STMPKit/Sources/STMPCore/\nA1\tios/changes/A1.md\n' > "$T/a.tsv"; TSV="$T/a.tsv"
  printf 'ios/STMPKit/Sources/STMPCore/Plugin.swift\nios/changes/A1.md\n' | check_paths A1 >/dev/null && r=0 || r=1; expect "A row owned" 0 $r
  printf 'ios/App/Sources/App/X.swift\n' | check_paths A1 >/dev/null && r=0 || r=1; expect "A row foreign" 1 $r
  # 6. One P-row case: refused until each base ticket is merged.
  printf 'P1\t@base=T5,T6\nP1\tios/STMPKit/Sources/STMPGate/\nP1\tios/scripts/ownership.tsv\n' > "$T/p.tsv"; TSV="$T/p.tsv"
  printf 'ios/STMPKit/Sources/STMPGate/G.swift\nios/changes/P1.md\n' | check_paths P1 >/dev/null && r=0 || r=1; expect "P row owned" 0 $r
  echo '{"tickets":{"T5":{"status":"merged"},"T6":{"status":"running"}}}' > "$T/rs.json"
  STMP_RUN_STATE="$T/rs.json" base_merged "T5,T6" >/dev/null && r=0 || r=1; expect "P refused while base unmerged" 1 $r
  echo '{"tickets":{"T5":{"status":"merged"},"T6":{"status":"merged"}}}' > "$T/rs.json"
  STMP_RUN_STATE="$T/rs.json" base_merged "T5,T6" >/dev/null && r=0 || r=1; expect "P allowed when merged" 0 $r
  STMP_RUN_STATE="" base_merged "T5" >/dev/null && r=0 || r=1; expect "P refused without run state" 1 $r
  [ $fail = 0 ] && echo "ownership self-test: ok" || exit 1
  exit 0
fi

[ -n "$TICKET" ] || { echo "ownership-check: --ticket required" >&2; exit 2; }
[[ "$TICKET" =~ ^(T[0-9]+|A[0-9]+|P[0-9]+)$ ]] || { echo "ownership-check: bad ticket $TICKET" >&2; exit 2; }
if [[ "$TICKET" == P* ]]; then
  bases=$(rows | awk -F'\t' -v t="$TICKET" '$1==t && $2 ~ /^@base=/ {sub(/^@base=/,"",$2); print $2}' | paste -sd, -)
  [ -n "$bases" ] || { echo "P ticket refused: no @base row for $TICKET in ownership.tsv"; exit 1; }
  base_merged "$bases" || exit 1
fi
if [ -n "$FILES_FROM" ]; then CHANGED=$(cat "$FILES_FROM"); else
  BASE=$(/usr/bin/git merge-base HEAD "${STMP_BASE:-ios/native-client}")
  CHANGED=$( { /usr/bin/git diff --name-only "$BASE"; /usr/bin/git ls-files -o --exclude-standard; } | sort -u )
fi
echo "$CHANGED" | check_paths "$TICKET" || { echo "ownership: FAIL"; exit 1; }
echo "ownership: ok ($TICKET)"
