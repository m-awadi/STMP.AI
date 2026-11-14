#!/bin/bash
# gen-contract.sh routes|errors|all   Regenerates ios/contract/{routes,errors}/*.json from the Android source at
# $STMP_BASELINE_SHA (read with `git show` only, never hand-typed; D103, D133).
#   routes: routes.json = method, path and the exempt set ONLY (the HelpContractTest regex + the gateExempt scan).
#           route-flags.json = verb, describe, plugin, flags per route, with _source/_expect at the declaration (D133).
#   errors: error-codes.json = ErrorCodes.kt MAP rows plus the unlisted emit sites and the iOS superset (D106).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
export STMP_ANDROID_REPO="${STMP_ANDROID_REPO:-/Users/mohamed/Developer/STMP.AI-integration}"
: "${STMP_BASELINE_SHA:?STMP_BASELINE_SHA must be set}"
export OUTDIR="${STMP_CONTRACT_OUT:-$ROOT/ios/contract}" WHAT="${1:-all}"
exec python3 -I - <<'PY'
import json, os, re, subprocess, sys
repo, sha, out, what = os.environ["STMP_ANDROID_REPO"], os.environ["STMP_BASELINE_SHA"], os.environ["OUTDIR"], os.environ["WHAT"]
def git(*a):
    r = subprocess.run(["git", "-C", repo, *a], capture_output=True, check=True); return r.stdout.decode()
MAIN = "app/src/main/java/com/mido/phonebridge/"; TEST = "app/src/test/java/com/mido/phonebridge/"
files = [f for f in git("ls-tree", "-r", "--name-only", sha, "--", "app/src/main").split("\n") if f.endswith(".kt")]
text = {f: git("show", f"{sha}:{f}").split("\n") for f in files}
def dump(rel, obj):
    p = os.path.join(out, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write(json.dumps(obj, indent=2, ensure_ascii=False) + "\n")
def line_of(f, pattern, start=0):
    for i in range(start, len(text[f])):
        if re.search(pattern, text[f][i]): return i + 1
    raise SystemExit(f"gen-contract: pattern {pattern!r} not found in {f}")
def src(f, ln): return f"android:{f}:{ln}@{sha}"

def gen_routes():
    pat = re.compile(r'"(GET|POST|PUT|PATCH|DELETE)",\s*"(/[^"]*)"')
    found = set(); exempt = set()
    for f in files:
        body = "\n".join(text[f])
        for m in pat.finditer(body): found.add(m.group(1) + " " + m.group(2).replace("$secretName", "{name}"))
        for i, l in enumerate(text[f]):
            if re.search(r"gateExempt\s*=\s*true", l):
                for j in range(i, -1, -1):
                    m = pat.search("\n".join(text[f][j:j+2]))
                    if m: exempt.add(m.group(1) + " " + m.group(2)); break
    helpt = git("show", f"{sha}:{TEST}HelpContractTest.kt").split("\n")
    pinned = {m.group(1) for l in helpt for m in [re.match(r'\s*"((?:GET|POST|PUT|PATCH|DELETE) /[^"]*)",?\s*$', l)] if m}
    if pinned != found: raise SystemExit(f"gen-contract: scan != PINNED_ROUTES: {sorted(pinned ^ found)}")
    ln = next(i + 1 for i, l in enumerate(helpt) if "PINNED_ROUTES: Set<String>" in l)
    routes = [{"method": r.split(" ")[0], "path": r.split(" ", 1)[1]} for r in sorted(found)]
    dump("routes/routes.json", {"_source": src(TEST + "HelpContractTest.kt", ln), "_expect": ["PINNED_ROUTES"],
         "routes": routes, "exempt": sorted(exempt)})
    gen_flags(found, exempt)

# plugin, file, method, path, describe, verb, {flag: value}, validate
P = lambda n: MAIN + n
T = [  # (plugin, file, method, path, describe, verb, flags, hasValidate, needsPassword)
 ("probe", "plugins/probe/ProbePlugin.kt", "GET", "/probe", "Check that the approval gate reaches you", "READ", {"aiSummary": False, "auditResponseBody": False}, False),
 ("help", "plugins/help/HelpPlugin.kt", "GET", "/help", "Describe this bridge and everything it can do", "READ", {"aiSummary": False, "auditResponseBody": False}, False),
 ("audio", "plugins/audio/AudioPlugin.kt", "POST", "/audio/{cue}", "PLAY AN ATTENTION CUE", "EXECUTE", {"gateExempt": True, "aiSummary": False, "auditRequestBody": False}, True),
 ("tts", "plugins/tts/TtsPlugin.kt", "POST", "/tts", "SAY TEXT ALOUD", "EXECUTE", {"gateExempt": True, "aiSummary": False}, True),
 ("gmail", "plugins/gmail/GmailPlugin.kt", "GET", "/gmail/profile", "Show your Gmail profile", "READ", {}, False),
 ("gmail", "plugins/gmail/GmailPlugin.kt", "GET", "/gmail/messages", "List your Gmail messages", "READ", {}, True),
 ("gmail", "plugins/gmail/GmailPlugin.kt", "GET", "/gmail/messages/{id}", "Read a Gmail message", "READ", {}, False),
 ("gmail", "plugins/gmail/GmailPlugin.kt", "POST", "/gmail/messages/{id}/modify", "Modify Gmail labels", "EDIT", {}, False),
 ("gmail", "plugins/gmail/GmailPlugin.kt", "POST", "/gmail/messages/{id}/trash", "TRASH EMAIL", "DELETE", {}, False),
 ("gmail", "plugins/gmail/GmailPlugin.kt", "POST", "/gmail/messages/send", "SEND EMAIL", "WRITE", {}, False),
 ("calendar", "plugins/calendar/CalendarPlugin.kt", "GET", "/calendar/events", "List your calendar events", "READ", {}, True),
 ("calendar", "plugins/calendar/CalendarPlugin.kt", "POST", "/calendar/events", "CREATE CALENDAR EVENT", "WRITE", {}, False),
 ("calendar", "plugins/calendar/CalendarPlugin.kt", "POST", "/calendar/events/{id}", "UPDATE CALENDAR EVENT", "EDIT", {}, False),
 ("calendar", "plugins/calendar/CalendarPlugin.kt", "DELETE", "/calendar/events/{id}", "DELETE CALENDAR EVENT", "DELETE", {}, False),
 ("calendar", "plugins/calendar/CalendarPlugin.kt", "POST", "/calendar/events/{id}/move", "MOVE CALENDAR EVENT", "EDIT", {}, False),
 ("calendar", "plugins/calendar/CalendarPlugin.kt", "POST", "/calendar/quickadd", "QUICK-ADD CALENDAR EVENT", "WRITE", {}, True),
 ("drive", "plugins/drive/DrivePlugin.kt", "GET", "/drive/files", "List or search your Drive files", "READ", {}, False),
 ("drive", "plugins/drive/DrivePlugin.kt", "GET", "/drive/files/{id}", "Show a Drive file's details", "READ", {}, False),
 ("drive", "plugins/drive/DrivePlugin.kt", "GET", "/drive/files/{id}/content", "DOWNLOAD a Drive file", "READ", {"auditResponseBody": False}, False),
 ("drive", "plugins/drive/DrivePlugin.kt", "POST", "/drive/files", "CREATE DRIVE FOLDER", "WRITE", {}, False),
 ("drive", "plugins/drive/DrivePlugin.kt", "POST", "/drive/files/{id}/rename", "RENAME DRIVE FILE", "EDIT", {}, False),
 ("drive", "plugins/drive/DrivePlugin.kt", "POST", "/drive/files/{id}/move", "MOVE DRIVE FILE", "EDIT", {}, False),
 ("drive", "plugins/drive/DrivePlugin.kt", "POST", "/drive/files/{id}/trash", "TRASH DRIVE FILE", "DELETE", {}, False),
 ("drive", "plugins/drive/DrivePlugin.kt", "POST", "/drive/files/upload", "UPLOAD FILE TO DRIVE", "WRITE", {"auditRequestBody": False, "streamsRequest": True}, False),
 ("contacts", "plugins/contacts/ContactsPlugin.kt", "GET", "/contacts", "Search your contacts", "READ", {"auditResponseTransform": True}, True),
 ("debug", "plugins/debug/DebugPlugin.kt", "GET", "/debug", "Read the phone's diagnostic report", "READ", {"aiSummary": False, "auditResponseBody": False}, False),
 ("debug", "plugins/debug/DebugPlugin.kt", "DELETE", "/audit", "DELETE THE REQUEST LOG", "DELETE", {"aiSummary": False}, False),
 ("logs", "plugins/logs/LogcatPlugin.kt", "GET", "/logs", "Read this app's own logcat", "READ", {"aiSummary": False, "auditResponseBody": False, "auditRequestBody": False}, True),
 ("notifications", "plugins/notifications/NotificationsPlugin.kt", "GET", "/notifications", "List your notifications", "READ", {}, True),
 ("notifications", "plugins/notifications/NotificationsPlugin.kt", "POST", "/notifications/{id}/action", "Fire a notification action", "EXECUTE", {}, False),
 ("notifications", "plugins/notifications/NotificationsPlugin.kt", "POST", "/notifications/{id}/dismiss", "Dismiss a notification", "EXECUTE", {"holdExempt": True}, False),
 ("secrets", "plugins/secrets/SecretIndexPlugin.kt", "GET", "/secrets", "List every secret's non-secret metadata (no values)", "READ", {}, False),
 ("secrets", "plugins/secrets/SecretIndexPlugin.kt", "GET", "/secrets/{name}", "Read one secret's non-secret metadata (no value)", "READ", {}, False),
 ("secrets", "plugins/secrets/SecretPlugin.kt", "POST", "/secrets/{name}/call", "Call an API with the '<name>' secret", "EXECUTE", {"needsVault": True}, True),
 ("ssh", "plugins/ssh/SshPlugin.kt", "POST", "/ssh/{name}", "RUN COMMAND OVER SSH", "EXECUTE", {"needsVault": True}, True),
 ("ssh", "plugins/ssh/SshPlugin.kt", "POST", "/ssh/{name}/detach", "START A DETACHED COMMAND OVER SSH", "EXECUTE", {"needsVault": True}, True),
 ("ssh", "plugins/ssh/SshPlugin.kt", "GET", "/ssh/job/{id}", "READ A DETACHED SSH JOB", "READ", {}, False),
]
DEFAULTS = {"auditRequestBody": True, "auditResponseBody": True, "auditResponseTransform": False, "streamsRequest": False,
            "aiSummary": True, "gateExempt": False, "holdExempt": False, "needsVault": False}
def gen_flags(found, exempt):
    entries = []
    assert {f"{t[2]} {t[3]}" for t in T} == found, sorted({f"{t[2]} {t[3]}" for t in T} ^ found)
    for plugin, rel, method, path, describe, verb, fl, validate in T:
        f = MAIN + rel
        # the declaration line: `"GET", "/path"` or route("GET", "/path" ; the broker path is built from $secretName.
        p2 = path.replace("{name}", "$secretName") if plugin == "secrets" and path.endswith("/call") else path
        ln = line_of(f, r'"%s",\s*"%s"' % (method, re.escape(p2)) if not (plugin == "secrets" and path.endswith("/call")) else r'"/secrets/\$secretName/call"')
        if plugin == "ssh" and path == "/ssh/job/{id}": ln = line_of(f, r'"GET", "/ssh/job/\{id\}"')
        checks, start = [], ln
        vexp = [f"Verb.{verb}"]
        if plugin == "secrets" and path.endswith("/call"): vexp = ["Verb.EXECUTE", "verbFor"]
        entry = {"method": method, "path": path, "plugin": plugin, "describe": describe, "verb": verb}
        if plugin == "secrets" and path.endswith("/call"): entry["verb"], entry["fallback"] = "verbFor", "EXECUTE"
        flags = dict(DEFAULTS); flags.update(fl)
        entry.update(flags); entry["validate"] = validate
        if plugin == "ssh" and path == "/ssh/{name}": entry["needsPassword"] = "sudo"
        elif plugin == "ssh": entry["needsPassword"] = "never"
        else: entry["needsPassword"] = None
        entry["_source"] = src(f, ln); entry["_expect"] = vexp
        for k, v in fl.items():
            lit = f"{k} = {'true' if v else 'false'}" if k != "auditResponseTransform" else "auditResponseTransform ="
            if plugin == "drive":
                fl_f = MAIN + "plugins/drive/DriveRouteFlags.kt"
                l2 = line_of(fl_f, r'Flags\("%s", "%s"' % (method, re.escape(path))); checks.append({"_source": src(fl_f, l2), "_expect": [lit]})
            else:
                l2 = line_of(f, re.escape(lit), ln - 1); checks.append({"_source": src(f, l2), "_expect": [lit]})
        if plugin == "ssh" and path == "/ssh/{name}":
            l2 = line_of(f, r"needsPassword = .*SudoCommand\.invokes", ln - 1); checks.append({"_source": src(f, l2), "_expect": ["needsPassword", "SudoCommand.invokes"]})
        if checks: entry["checks"] = checks
        entries.append(entry)
    dump("routes/route-flags.json", {"_source": src(MAIN + "core/Plugin.kt", line_of(MAIN + "core/Plugin.kt", r"data class Route\(")),
         "_expect": ["data class Route("], "routes": entries})
    dump("routes/routes-ios.json", {"_source": "ios:Q12", "routes": [
        {"key": f"{m} {p}", "unsupportedOnIOS": "Android notifications cannot be read or acted on from an iPhone app; iOS offers no equivalent API.", "_source": "ios:Q12"}
        for m, p in (("GET", "/notifications"), ("POST", "/notifications/{id}/action"), ("POST", "/notifications/{id}/dismiss"))]})

def gen_errors():
    f = MAIN + "core/ErrorCodes.kt"; rows = []
    inmap = False
    for i, l in enumerate(text[f]):
        if "private val MAP" in l: inmap = True; continue
        if inmap and l.strip() == ")": break
        m = re.match(r'\s*"([a-z_]+)" to ([A-Z_]+),?\s*$', l) if inmap else None
        if m: rows.append((m.group(1), m.group(2), i + 1))
    st = {"BAD_REQUEST": 400, "UNAUTHORIZED": 401, "FORBIDDEN": 403, "NOT_FOUND": 404, "CONFLICT": 409, "GONE": 410, "INTERNAL": 500, "UNAVAILABLE": 503}
    ents = [{"code": c, "status": st[s], "listed": True, "_source": src(f, ln), "_expect": [f'"{c}" to {s}']} for c, s, ln in rows]
    unl = [("approval_timeout", 408, "BridgeService.kt", r'err\("approval_timeout"'), ("caller_registry_unavailable", 500, "BridgeService.kt", r'err\(\s*$|draft.error\("caller_registry_unavailable"\)'),
           ("audit_unavailable", 500, "BridgeService.kt", r'err\("audit_unavailable"'), ("logcat_failed", 500, "plugins/logs/LogcatPlugin.kt", r'"logcat_failed"')]
    for code, status, rel, pat in unl:
        ff = MAIN + rel; ln = line_of(ff, re.escape(code) if code != "approval_timeout" else r'err\("approval_timeout"')
        ents.append({"code": code, "status": status, "listed": False, "_source": src(ff, ln), "_expect": [code]})
    ents.append({"code": "not_supported_on_ios", "status": 501, "listed": True, "platform": "ios", "_source": "ios:Q5"})
    dump("errors/error-codes.json", {"_source": src(f, line_of(f, r"private val MAP")), "_expect": ["private val MAP"], "codes": ents})

if what in ("routes", "all"): gen_routes()
if what in ("errors", "all"): gen_errors()
print(f"gen-contract: wrote {what} into {out}")
PY
