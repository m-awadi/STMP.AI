#!/bin/bash
# resolved-check.sh [--write]   Pin hygiene (D91, D124).
#  - every identity in both ios/Package.resolved and ios/STMPKit/Package.resolved has the same revision;
#  - every runtime package (a pin of STMPKit) is in the transitive closure of swift-nio-ssh and swift-nio, computed from
#    `swift package show-dependencies --format json`; nothing else may be runtime;
#  - every resolved package appears in THIRD-PARTY-NOTICES.md with a licence and a `runtime` or `test-only` mark.
#  --write regenerates ios/Package.resolved (xcodegen, then xcodebuild -resolvePackageDependencies; needs the network).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); IOS=$(cd "$HERE/.." && pwd); OUT="${STMP_OUT:-$IOS/.derived}"; PKGS="${STMP_PKGS:-$OUT/SourcePackages}"
KIT="$IOS/STMPKit/Package.resolved"; APPR="$IOS/Package.resolved"
if [ "${1:-}" = "--write" ]; then
  /opt/homebrew/bin/xcodegen generate --spec "$IOS/project.yml" --quiet
  xcodebuild -resolvePackageDependencies -project "$IOS/STMP.xcodeproj" -scheme STMP -clonedSourcePackagesDirPath "$PKGS" >/dev/null
  # xcodebuild keeps the resolved versions in workspace-state.json; ios/Package.resolved is derived from it (STMPKit pins plus the test-only pin).
  python3 -I - "$PKGS/workspace-state.json" "$APPR" <<'PYW'
import json, sys
st = json.load(open(sys.argv[1]))["object"]["dependencies"]
pins = []
for d in st:
    ref, cs = d["packageRef"], d["state"].get("checkoutState")
    if ref["kind"] != "remoteSourceControl" or not cs: continue
    pins.append({"identity": ref["identity"], "kind": ref["kind"], "location": ref["location"], "state": {"revision": cs["revision"], "version": cs.get("version")}})
pins.sort(key=lambda p: p["identity"])
json.dump({"pins": pins, "version": 3}, open(sys.argv[2], "w"), indent=2, sort_keys=True); open(sys.argv[2], "a").write("\n")
PYW
  echo "resolved-check: wrote $APPR"; exit 0
fi
fail() { echo "resolved-check: FAIL $1" >&2; exit 1; }
[ -f "$KIT" ] || fail "$KIT is missing"; [ -f "$APPR" ] || fail "$APPR is missing (run resolved-check.sh --write)"
SCRATCH="${STMP_SPM:-$OUT/spm-host}"
DEPS=$(swift package --package-path "$IOS/STMPKit" --scratch-path "$SCRATCH" show-dependencies --format json 2>/dev/null) || fail "show-dependencies failed (network? package not resolved?)"
export KIT APPR NOTICES="$IOS/THIRD-PARTY-NOTICES.md" DEPS
exec python3 -I - <<'PY'
import json, os, re, sys
kit = {p["identity"]: p["state"].get("revision") for p in json.load(open(os.environ["KIT"]))["pins"]}
app = {p["identity"]: p["state"].get("revision") for p in json.load(open(os.environ["APPR"]))["pins"]}
errs = []
for i in kit.keys() & app.keys():
    if kit[i] != app[i]: errs.append(f"revision differs for {i}: package {kit[i]} app {app[i]}")
for i in kit.keys() - app.keys(): errs.append(f"{i} is pinned in STMPKit but missing from ios/Package.resolved")
tree = json.loads(os.environ["DEPS"])
def ident(n): return (n.get("identity") or n.get("name") or "").lower()
def closure(n, acc):
    acc.add(ident(n))
    for d in n.get("dependencies", []): closure(d, acc)
    return acc
allowed = set()
for top in tree.get("dependencies", []):
    if ident(top) in ("swift-nio-ssh", "swift-nio"): closure(top, allowed)
for i in kit:
    if i not in allowed: errs.append(f"runtime package {i} is outside the closure of swift-nio-ssh and swift-nio")
notices = open(os.environ["NOTICES"]).read() if os.path.exists(os.environ["NOTICES"]) else ""
for i in sorted(kit.keys() | app.keys()):
    kind = "runtime" if i in kit else "test-only"
    rows = [l for l in notices.split("\n") if re.search(r"(?<![\w-])" + re.escape(i) + r"(?![\w-])", l) and l.startswith("|")]
    if not rows: errs.append(f"{i} is missing from THIRD-PARTY-NOTICES.md"); continue
    if not any(kind in r and re.search(r"Apache|MIT|BSD|ISC|OFL|licen", r, re.I) for r in rows): errs.append(f"{i}: THIRD-PARTY-NOTICES.md row needs a licence and '{kind}'")
if errs:
    for e in errs: print("resolved-check: FAIL " + e)
    sys.exit(1)
print(f"resolved-check: ok ({len(kit)} runtime pins in the closure {sorted(allowed)}; {len(app.keys()-kit.keys())} test-only)")
PY
