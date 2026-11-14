#!/bin/bash
# source-check.sh [--tip]   Verifies every `_source` in ios/contract/**/*.json (D59, D103, D134).
#   android:<path>:<line>@<sha>  sha == $STMP_BASELINE_SHA (or listed in $STMP_EXTRA_SHAS), the file exists at that sha,
#                                the line exists and is not blank, and every `_expect` literal occurs within +-8 lines.
#   ios:<Dn|Qn>                  format only.
# --tip re-checks _expect against the Android tip and prints `DRIFT <file> <entry>` (warn only; used by contract-drift.sh).
# Vendored files under contract/**/vendored/ need a sidecar <name>.source.json and must be byte-identical (D117).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
export STMP_ANDROID_REPO="${STMP_ANDROID_REPO:-/Users/mohamed/Developer/STMP.AI-integration}"
export CONTRACT_DIR="${STMP_CONTRACT_DIR:-$ROOT/ios/contract}"
export TIP=0; [ "${1:-}" = "--tip" ] && TIP=1
exec python3 -I - <<'PY'
import json, os, re, subprocess, sys
repo = os.environ["STMP_ANDROID_REPO"]; base = os.environ.get("STMP_BASELINE_SHA", "")
extra = set(filter(None, os.environ.get("STMP_EXTRA_SHAS", "").split(","))); tip = os.environ["TIP"] == "1"
cdir = os.environ["CONTRACT_DIR"]
NEED_EXPECT = {"pipeline/cases.json", "gate/notify-outcomes.json", "gate/approve-gesture.json", "errors/error-codes.json", "routes/route-flags.json"}
SRC = re.compile(r"^android:([^:]+):(\d+)@([0-9a-f]{7,40})$"); IOS = re.compile(r"^ios:[DQ]\d+$")
errors, warns, checked = [], [], 0
def git(*a):
    r = subprocess.run(["git", "-C", repo, *a], capture_output=True)
    return r.returncode, r.stdout
tipsha = git("rev-parse", "redesign/instrument")[1].decode().strip() if tip else ""
cache = {}
def lines_of(sha, path):
    k = (sha, path)
    if k not in cache:
        rc, out = git("show", f"{sha}:{path}"); cache[k] = out.decode("utf-8", "replace").split("\n") if rc == 0 else None
    return cache[k]
def check(rel, where, d):
    global checked
    src = d["_source"]; exp = d.get("_expect")
    if not isinstance(src, str): errors.append(f"{rel} {where}: _source is not a string"); return
    m = SRC.match(src)
    if not m:
        if not IOS.match(src): errors.append(f"{rel} {where}: bad _source grammar '{src}'")
        return
    path, line, sha = m.group(1), int(m.group(2)), m.group(3)
    checked += 1
    if tip:
        sha = tipsha
    else:
        if not base: errors.append("STMP_BASELINE_SHA is not set"); return
        if not (base.startswith(sha) or sha.startswith(base) or sha in extra): errors.append(f"{rel} {where}: sha {sha} is not the baseline"); return
    ls = lines_of(sha, path)
    if ls is None: (warns if tip else errors).append(f"{'DRIFT ' if tip else ''}{rel} {where}: {path} missing at {sha[:9]}"); return
    if line < 1 or line > len(ls) or not ls[line-1].strip():
        (warns if tip else errors).append(f"{'DRIFT ' if tip else ''}{rel} {where}: line {line} of {path} is missing or blank"); return
    if exp is None:
        if rel in NEED_EXPECT and not tip: errors.append(f"{rel} {where}: _expect is required on an android: row")
        return
    win = "\n".join(ls[max(0, line-9): line+8])
    for lit in exp:
        if lit not in win: (warns if tip else errors).append(f"{'DRIFT ' if tip else ''}{rel} {where}: _expect '{lit}' not within +-8 lines of {path}:{line}")
def walk(rel, where, node):
    if isinstance(node, dict):
        if "_source" in node: check(rel, where, node)
        for k, v in node.items():
            if k not in ("_source", "_expect"): walk(rel, where + "/" + k, v)
    elif isinstance(node, list):
        for i, v in enumerate(node): walk(rel, f"{where}[{i}]", v)
for dp, dn, fn in os.walk(cdir):
    for f in sorted(fn):
        full = os.path.join(dp, f); rel = os.path.relpath(full, cdir)
        if "/vendored/" in "/" + os.path.relpath(dp, cdir) + "/" and not f.endswith(".source.json"):
            side = full.rsplit(".", 1)[0] + ".source.json"
            if not os.path.exists(side): errors.append(f"{rel}: vendored file needs a sidecar {os.path.basename(side)}"); continue
            sj = json.load(open(side)); m = SRC.match(sj.get("_source", ""))
            if not m: errors.append(f"{rel}: sidecar _source invalid"); continue
            rc, out = git("show", f"{m.group(3)}:{m.group(1)}")
            if rc != 0 or out != open(full, "rb").read(): errors.append(f"{rel}: not byte-identical to {m.group(1)}@{m.group(3)[:9]}")
            continue
        if f.endswith(".json"):
            try: walk(rel, "", json.load(open(full)))
            except Exception as e: errors.append(f"{rel}: not valid JSON ({e})")
for w in warns: print(w)
if errors:
    for e in errors: print("source-check: FAIL " + e)
    sys.exit(1)
print(f"source-check: ok ({checked} android sources{' vs tip' if tip else ''})")
PY
