#!/bin/bash
# changelog-check.sh [--ticket ID] [--merge]  (port of Android tools/changelog-check.ts, PLAN 3 step 4)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); IOS=$(cd "$HERE/.." && pwd); ROOT=$(cd "$IOS/.." && pwd)
CL="$IOS/CHANGELOG.md"; VER="$IOS/Config/Version.xcconfig"; MARKER="## Keeping this file honest"
TICKET=""; MERGE=0
while [ $# -gt 0 ]; do case "$1" in --ticket) TICKET="$2"; shift 2;; --merge) MERGE=1; shift;; *) echo "changelog-check: bad arg $1" >&2; exit 2;; esac; done
fail() { echo "changelog-check: FAIL - $1" >&2; exit 1; }
[ -f "$CL" ] || fail "$CL not found"; [ -f "$VER" ] || fail "$VER not found"
grep -qF "$MARKER" "$CL" || fail "missing the '$MARKER' section"
BODY=$(awk -v m="$MARKER" 'index($0,m)==1{exit} {print}' "$CL")
ENTRIES=$(printf '%s\n' "$BODY" | grep '^## ' || true)
[ -n "$ENTRIES" ] || fail "no release entries found"
NEWEST=$(printf '%s\n' "$ENTRIES" | tail -1)
CUR_N=$(printf '%s\n' "$ENTRIES" | grep -c -- '— CURRENT' || true)
[ "$CUR_N" -ge 1 ] || fail "no entry is marked '— CURRENT'"
[ "$CUR_N" -le 1 ] || fail "more than one entry is marked '— CURRENT'"
printf '%s\n' "$NEWEST" | grep -q -- '— CURRENT' || fail "'— CURRENT' is on a stale entry (newest: $NEWEST)"
printf '%s\n' "$NEWEST" | grep -Eq '^## [0-9]+\.[0-9]+\.[0-9]+ \(build [0-9]+\) — [0-9]{4}-[0-9]{2}-[0-9]{2} — CURRENT$' || fail "newest heading is not '## X.Y.Z (build N) — YYYY-MM-DD — CURRENT': $NEWEST"
E_VER=$(printf '%s' "$NEWEST" | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+' | head -1); E_BUILD=$(printf '%s' "$NEWEST" | sed -E 's/.*\(build ([0-9]+)\).*/\1/')
X_VER=$(sed -nE 's/^MARKETING_VERSION *= *([0-9.]+).*/\1/p' "$VER" | head -1); X_BUILD=$(sed -nE 's/^CURRENT_PROJECT_VERSION *= *([0-9]+).*/\1/p' "$VER" | head -1)
[ "$E_VER" = "$X_VER" ] || fail "version disagrees: changelog $E_VER, Version.xcconfig $X_VER"
[ "$E_BUILD" = "$X_BUILD" ] || fail "build disagrees: changelog $E_BUILD, Version.xcconfig $X_BUILD"
! printf '%s\n' "$ENTRIES" | grep -q '<hash>' || fail "unfinished '<hash>' placeholder in an entry"
! grep -Eq '^(<{7}|\|{7}|={7}|>{7})([[:space:]]|$)' "$CL" || fail "leftover merge-conflict marker"
if [ -n "$TICKET" ]; then
  [[ "$TICKET" =~ ^(T|A|P)[0-9]+$ ]] || fail "bad ticket id $TICKET"
  if [ "$TICKET" != "T0" ]; then   # T0 creates both files
    BASE=$(/usr/bin/git -C "$ROOT" merge-base HEAD "${STMP_BASE:-ios/native-client}")
    [ -z "$(/usr/bin/git -C "$ROOT" diff --name-only "$BASE" -- ios/CHANGELOG.md ios/Config/Version.xcconfig)" ] || fail "ios/CHANGELOG.md or Version.xcconfig changed since BASE"
  fi
  F="$IOS/changes/$TICKET.md"; [ -f "$F" ] || fail "ios/changes/$TICKET.md is missing"
  N=0
  while IFS= read -r line || [ -n "$line" ]; do
    [ -z "$line" ] && continue
    [[ "$line" == "- "* ]] || fail "$F: only '- ' bullets allowed: $line"
    S=$(printf '%s' "${line#- }" | sed -E 's/[0-9]\.[0-9]/N/g; s/e\.g\./eg/g; s/i\.e\./ie/g' | grep -Eo '[.!?]( |$)' | wc -l | tr -d ' ')
    [ "$S" -le 2 ] || fail "$F: bullet runs past two sentences: $line"
    N=$((N+1))
  done < "$F"
  [ $N -gt 0 ] || fail "$F has no bullets"
fi
if [ $MERGE = 1 ]; then
  EXTRA=$(ls "$IOS/changes" | grep -v '^README.md$' || true); [ -z "$EXTRA" ] || fail "ios/changes/ holds more than README.md: $EXTRA"
fi
echo "changelog-check: ok - $(printf '%s\n' "$ENTRIES" | wc -l | tr -d ' ') entries, current $X_VER / build $X_BUILD"
