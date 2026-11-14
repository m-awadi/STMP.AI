# Changelog — PhoneBridge

The full history of the PhoneBridge Android app (`com.mido.phonebridge`), from the first
commit to the current build. Chronological: oldest first, so it reads as the story of how the
app got here.

**This file is updated on EVERY commit** (see "Keeping this file honest" at the bottom). If you
changed behaviour, the entry goes here in the same commit — a version bump without a changelog
line is an incomplete change.

Format per entry: what shipped, and **why** — the reasoning is the part that stops a future
change from undoing it. Dates are on each heading; find a release's commit with
`git log --oneline --grep "<version>"` — see the note below on why an entry cannot carry its own
hash.

Current build: **v0.52.20 (build 335)**.

---

## Initial — Add the PhoneBridge Android app
`a57e0ad` · 2026-10-15

The app is born: a native Android HTTP server that runs on the phone so a remote caller over
Tailscale can read notifications and use Gmail.

- Notification bridge + plugin architecture, Gmail plugin, approval gate.
- UI repaint, full notification-extras extraction.
- Android Lint wired into the build.
- Fixed: login-activity manifest crash, and a listener-ref status bug.
- Gitignores Android build artifacts.

Committed as a snapshot **before** ripping out the loopback OAuth flow in favour of Google Play
Services / Credential Manager login.

## Housekeeping — snapshot
`e1f9332` · 2026-10-17

Snapshot taken before a job-finds-digest payload repair (the local unsloth endpoint was down).

## 0.3.3 — `not_found` maps to HTTP 404
`61c23c5` · 2026-10-17

A bad-id pre-fetch (Gmail trash, Calendar delete/update/move) threw a **correct** `not_found`
body but `httpStatusFor` had no entry for it, so it fell through to `500`. Caller-visible:
`500 → 404`, body identical. One missing mapper line, one wrong status.

## Plan — audit log + request policy
`74771e0` · 2026-10-17

The release plan for the two big features. Four isolated releases: R1 audit spine (Route gains a
required verb/scope, gate returns `GateResult` instead of throwing, append-only JSONL day-files,
log screen), R2 policy engine, R3 `NOTIFY`, R4 log UX.

Locked decisions: audit-append failure denies the request; unattended `SECURE`/`NOTIFY` deny
while `AUTO` approves; biometric is `SECURE`-only (supersedes v0.3.1); no hidden rules; log
everything with a derived noise flag.

## Plan — wildcard cascade replaced by global switch + exact rules
`c2422fc` · 2026-10-17

R2 becomes a global gate mode (`AUTOMATIC | SECURE | NOTIFY | OFF`, default `SECURE`) plus an
exact per-pair rule table keyed on `(plugin, verb)` — **no wildcards**. `AUTOMATIC` is the only
value that consults the table; every other value forces its mode on all requests, which is what
makes `OFF` a true panic switch.

Deleted: the specificity tuple, tie-breaks, the effective-mode matrix, the `scope` field on
`Route`, and Q11 (with no wildcards, two rules can never both match).

## Plan — per-pair `OFF` locked as global-only
`64b3cfb` · 2026-10-17

The pair picker stays `default | notify | secure`. Auto-approve is expressible **only** through
the global switch, so an accidentally-set auto-approve row cannot exist.

## 0.4.0 — the audit spine (R1)
`97cb455` · 2026-10-17

Every request now writes exactly one append-only JSONL record before the HTTP response returns,
and every route declares what it does.

- `core/Labels.kt`: `Verb` (5 values), `Origin`, `originOf()`, label `plugin.verb`. `Route` gains
  a **required** verb — not derived from the HTTP method, because `POST /notifications/{id}/action`
  is `EXECUTE` and `POST /gmail/messages/{id}/trash` is `DELETE`.
- `audit/`: `AuditRecord` + `AuditDraft` (one draft per request, written-once guard), `AuditLog`
  (day-files, `File` not `Context`, so retention/corruption handling is JVM-tested),
  `AuditNoise`, `AuditHealth`.
- `ApprovalGate.await()` returns `GateResult` (`Approved`/`Denied`/`TimedOut`) instead of
  throwing, and stamps shown/decided timestamps for the record.
- `serve()` runs one dispatch that sets a terminal outcome on every path, then appends. A failed
  append replaces the response with `500 audit_unavailable` and raises a home-screen banner.
- `LogActivity` + `LogDetailActivity`: newest-first list, noise hidden by default, verbatim
  detail incl. truncation sizes and the decision trace.
- **20 JVM tests.** One caught a real bug: a random id suffix could collide within a millisecond,
  and `line(id)` matches on prefix, so it could return the **wrong** record. Now a deterministic
  counter.

Gating behaviour unchanged: every request still opens the modal, biometrics still required, deny
still `403`, timeout still `408`.

## 0.4.1 — the noise chip label flips with its state
`df299a2` · 2026-10-17

The chip was hardcoded `hide noise`, so it said the same thing whether noise was visible or
hidden — a label that lies half the time. Now it names the action: `show noise` while hidden,
`hide noise` once shown. Caught on the device from two screenshots of the same screen in both
states.

## 0.4.2 — validate before the gate; stop mislabelling bad input
`80665f2` · 2026-10-17

Two device-log bugs, one cause: input validation lived inside route handlers, which run **after**
the approval gate.

1. `?count=-1` opened an approval popup. Input that can only ever produce a `400` should never
   cost a tap.
2. That same request was recorded `HANDLER_FAILED` — the loudest badge — because it failed after
   the gate ran. `HANDLER_FAILED` is deliberately not noise, so a client typo was promoted into
   the signal stream and hid itself from the default view.

Fix: `Route` gains an optional `validate` hook that dispatch invokes **before** the modal is
built. Each plugin reuses the **same** parser in the hook and the handler, so the paths cannot
drift. Failure before the gate is a `4xx` by construction.

The classification itself becomes `AuditClassify`, a pure tested function instead of four lines
inline in a `catch`. It keeps one deliberate asymmetry: a `4xx` arriving **after** a human
approved stays `HANDLER_FAILED`, because that wasted a real decision.

Two sibling defects fixed while wiring it:

- Calendar `require(timeMin < timeMax)` threw `IllegalArgumentException`, escaping as a generic
  `500`. Now a coded `bad_time_range` `400`.
- Gmail `max` was silently clamped (`(toIntOrNull() ?: 20).coerceIn(1,100)`): `max=5000` returned
  100 rows with no clue, `max=abc` returned 20. Now a pre-gate `bad_max` `400`.

**27 tests** (7 new for `AuditClassify`, pinning the exact device-log bug).

## 0.5.0 — the global gate switch, and OFF
`1f0f295` · 2026-10-17

R2, scoped to one thing after the notify cut: the switch and the mode it selects.

The cut's consequence: with only `secure` and `off` available, a per-pair rule could only say
`secure` — which is exactly what "no rule" already means. A rule table with one expressible value
is machinery with no observable effect, so rules moved to R3. Same reasoning removed `automatic`.
R2 ships `SECURE | OFF`.

- `policy/PolicyMode.kt` — `GlobalMode`, `Mode`, `resolveMode`
- `policy/Policy.kt` — strict parse; unknown value = error, not a default
- `policy/PolicyStore.kt` — atomic write (temp + fsync + rename), cached
- `audit/PolicyAudit.kt` — the `POLICY_CHANGED` record
- `audit/AuditId.kt` — id format extracted so policy records cannot collide
- `MainActivity` — the switch card, OFF confirmation, red banner

Design calls worth naming:

- `resolveMode` has **no `else` branch**. Adding `NOTIFY`/`AUTOMATIC` in R3 becomes a compile
  error at that `when` — the compiler asks where the new value goes.
- An unknown `global` string is a **parse error**, not a silent `SECURE`. Downgrading must fail
  loudly; quietly reattaching a gate the user turned off is a change they never asked for.
- A non-empty `rules` array is refused by this build. It cannot honour a rule, so running on the
  file would mean ignoring a decision sitting right there.
- The server refuses to start on an unreadable policy. "Assume secure" is still an assumption.
- The `rules` array is written from R2 and always empty, so R3 needs no file migration.
- `POLICY_CHANGED` is never noise — turning the gate off is the loudest line in the log.

**38 tests** (11 new; the happy path is one, the rest are failure modes).

## 0.5.1 — `GET /debug`, and record the bodies that never parsed
`8945914` · 2026-10-17

Two halves of the same complaint: the phone kept no evidence and exposed no state.

1. A body that failed to parse was answered `400 invalid JSON body` with **zero** record of what
   was sent — `readBody` parsed first and threw, so the draft never saw the bytes. Found by
   testing the malformed path, not by reading code. Fix splits reading from parsing: `readRawBody`
   returns the text as sent, `parseBody` throws. The raw text lands on the draft **before** the
   parse. Applies to empty bodies too.
2. `GET /debug` — a diagnostic report so questions about the phone do not each cost a round trip
   through chat. App/server/storage/account/policy/audit state, the last 50 access-log entries,
   the redacted debug ring, and the route table. **Shape-only** for anything sensitive: lengths
   and booleans, never a token value.

Supporting changes:

- `Route.auditResponseBody` (default `true`). `/debug` returns the log tail, so storing its
  response copies the log into the log — and at the 32 KiB blob cap it eats a large share of the
  20 MB retention budget.
- `Truncation.responseBodyAbsent` — an unrecorded response must not read as an empty one.
- `AuditStore.storageStats()` — on-disk day-files, read fresh.

**42 tests** (4 new). One test was written and then **deleted**: it re-declared the `/debug` route
inside the test to check the opt-out flag, so it would have passed even if the real plugin
changed. A test that cannot fail is worse than no test.

## 0.5.2 — one response-recording path, so OFF cannot bypass the opt-out
`623cadf` · 2026-10-17

Found on device, verifying the `OFF` switch — which is exactly why that switch needed verifying.

`Route.auditResponseBody` was honoured on the gated path and **ignored** on the `OFF` path: that
branch returned early and called `draft.response(...)` directly. Result: `/debug` stored its
entire ~11.5 KB report into the log on **every** call whenever the gate was off.

Measured twice independently: `+1 record, +13612 bytes` and `+1 record, +13846 bytes`, versus
~1 KB for the same record under `SECURE`.

Fix: the decision moved into `audit/AuditResponse.recordResponse()`, called by both paths — one
handler call and one recording site now. A first attempt also worked but put the decision in a
**private** method, which nothing could test and any future branch could bypass; that is the same
defect in a different hat, so it was thrown away for the shared function.

**44 tests.** The old `RouteAuditTest` constructed a `Route` and asserted on the flag — it passed
while the real dispatcher ignored that flag on half its paths. It now calls the same function the
dispatcher calls, including a test that the `OFF` and gated paths produce identical records.

Also device-verified: the `OFF` switch itself (`OFF_APPROVED`, `200`, 376ms, no popup;
`POLICY_CHANGED` on toggle).

## Plan — R3 expanded to full detail
`50194ea` · 2026-10-17

§7 was architecture-level while R1/R2 were fully specced. Worth doing before code: R3 is the first
release where a **stored decision changes behaviour**, so a policy bug stops being a display bug
and becomes a security bug.

Settled: the switch gains `AUTOMATIC` and `NOTIFY` (4 values); `AUTOMATIC + no rule → SECURE`
("silence means ask me"); `NOTIFY` mechanics (separate channel, 5s from POST, silence = approve,
`X` = reject, caller still gets plain `403 denied`); `isAttended()` checked **twice**; biometric
computed as `(mode == SECURE)`; `resolveMode(global, rules, plugin, verb)` is a `find` on a list;
a queue rule (NOTIFY never queues behind the 60s modal).

Caught while writing: the R2 forward-compat test asserting `"AUTOMATIC"` is a parse error becomes
vacuous the moment AUTOMATIC ships — flagged to switch to a genuinely unknown literal. And the doc
still claimed R2 shipped the rule table; it did not.

Plan only. No source touched.

## 0.6.0 — NOTIFY as a draw-over-apps toast, plus exact rules
`6b5ae7b` · 2026-10-17

R3. The first release where a stored decision changes behaviour.

**NOTIFY is an overlay, not a notification.** The plan said "separate channel, IMPORTANCE_HIGH";
Mido overruled it, correctly: a heads-up notification is a system-owned surface that hijacks the
shade, fights DND, and is suppressed while the phone is in use — exactly when a request arrives.
So the prompt is a small overlay drawn with `SYSTEM_ALERT_WINDOW`.

The item: a dark pill, two lines of monospace, a 2dp bar under the text that drains over the
window (the countdown **is** the bar, so the visual and timer cannot disagree), a tiny `x`
(28dp touch box), fade in 180ms, fade out 200ms, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` so it
never takes input.

Items stack up to 4, each with its own window. **Failing to draw is a deny** — if the overlay
cannot be shown, the request is `DENIED_UNATTENDED`. "We failed to ask" and "nobody objected" are
not the same fact, and only one of them is consent.

Policy: exact rules, `AUTOMATIC`, and the rules screen.

- `Rule(plugin, verb, mode)` is exact — no wildcard, no scope, no cascade, so two rules can never
  match one request and there is nothing to rank.
- `AUTOMATIC + no rule → SECURE`.
- Rules may carry `NOTIFY` or `SECURE` only. `OFF` stays global-only: making "no human, no
  fingerprint" settable twelve times invites one accidental row.
- Duplicate pair, unknown verb/mode, wildcard plugin, and `OFF`-as-a-rule are all parse errors.

Found and fixed while building:

- `applyGate` built `Policy(after)` from scratch, which would have **deleted every rule** on a
  switch flip. Rules are carried across now.
- The R2 test asserting `"AUTOMATIC"` is a parse error became vacuous — rewritten to a genuinely
  unknown literal with a comment saying why.
- `NotifyOverlay` had a placeholder-Item hack and a `val text` shadowing `TextView.text`.
- `shownAt` was stamped before `main.post` ran, inflating every recorded duration.

**56 tests.** The rules screen exists because `AUTOMATIC` without it is a switch position that
does nothing.

## 0.6.1 — NOTIFY is a queue, not a stack
`6352258` · 2026-10-17

Mido's device report was one line and it killed the design: *"3 at once I saw only one timer.
queue is better."*

Every in-flight prompt was drawn at the same coordinate, so three simultaneous requests produced
**one** visible card with **one** countdown. Two windows expired behind a bar the human could not
attribute to them. The stack was wrong twice over: it hid requests, **and** it burned their clocks
while unreadable. My own note had said "stacking confirmed" because I measured durations instead
of looking at the screen.

Reversed: one card on screen at a time, the rest wait invisibly in arrival order. A waiting
request has **no clock** — its window starts when it reaches the screen.

Ordering and capacity moved into `notify/NotifyQueue.kt`, which is **pure** (no Android, no views,
no clocks) — the broken part is now the part a unit test can prove. `NotifyQueueTest`, 12 tests,
covering the exact case that failed.

**No eviction, ever.** 0.6.0 dropped the oldest item when out of room, denying a request the human
had been shown. Now the line holds one on screen + three waiting; a fourth is **refused**, and the
newcomer is the one denied.

Also: rounded corners (14dp, outline provider), bottom-left anchor, draggable (8dp slop). The
countdown became the card's **background** so the whole card drains.

Caught while wiring: `ESCAPE_MS` was a hardcoded 15s, **shorter** than the worst legitimate wait
once four prompts are in the system (4×5s) — a request merely waiting its turn would have been
denied as "outcome never arrived". Now derived from the queue's own capacity.

**68 tests** (up from 56).

## 0.6.2 — the full-screen card, fixed
`de6ede6` · 2026-10-17

Mido saw it: *"a VERY long card, almost full screen height."*

The countdown fill was a child View added as `MATCH_PARENT × MATCH_PARENT` into a `FrameLayout`
whose height is `WRAP_CONTENT`. In a `WRAP_CONTENT` FrameLayout, a `MATCH_PARENT` child forces the
parent to match **its** parent — the full window. The card stretched to fill the screen.

Fix: move the drain from a child view into the card's own background as a `LayerDrawable`. A
background layer cannot participate in layout, so the card's size is decided by the text row alone
— 232dp wide, one or two lines high.

Also fixed the intermittent "1ms flash": the fade-out translated by `card.height / 4`, and when a
prompt ends before first layout, height is 0 — a zero-distance translate. Now a fixed 10dp.

**68 tests.** The layout bug was invisible to them for a reason: unit tests prove ordering and the
clock; they cannot prove the pixels.

## 0.7.0 — `GET /logs`, and the overlay starts describing itself
`5594131` · 2026-10-17

`GET /logs` — the logcat endpoint. A separate route from `/debug` deliberately: `/debug` answers
"what is the state", this answers "what happened, line by line".

- **Own process only.** Since Android 4.1 an app may read only its own UID's log lines, with no
  permission — so no manifest permission, and no way to leak another app's logs.
- `?lines=` (default 200, cap 500) and `?filter=`, both validated **pre-gate**.
- `logcat -d` (dump-and-exit), `-t` bound, char-bounded reader (256 KiB).
- A failure to read is a failure, not an empty list: `500 logcat_failed`.

**The overlay now describes itself.** Mido reported the prompt as a "1ms flash" twice, and both
times the app's in-memory ring could not say why. So the overlay logs its lifecycle permanently:
`attached`, `laid out` (with w/h/alpha/vis), `settle` (with reason + shownMs). That is the
difference between a mystery and a measurement.

## 0.8.0 — the invisible card, and a real fix for it
`81fb38b` · 2026-10-17

`/logs` paid for itself on the first run:

```
notify[5001] laid out w=435 h=86 alpha=0.0 vis=0
notify[5001] settle reason=EXPIRED shownMs=5015
```

Read it carefully: 435×86 px is 232×46 dp — **the card is small, the 0.6.2 layout fix worked**.
It lived 5.27s in the window manager — **the clock is correct**. It settled `EXPIRED` — the right
path. So the window is present, correctly sized, for the full five seconds — and the human still
sees a 1ms flash. **The card is there and invisible.** Both earlier fixes were about size and
timing, and size and timing were already fine.

Two candidates, both addressed:

1. `clipToOutline` (**removed**) — its outline provider read `view.width`/`view.height`, and an
   outline computed before first layout is an empty round-rect, which clips the entire card to
   nothing.
2. The fade-in never finishing — if that animator does not run, alpha stays 0 and the card is
   invisible for its whole window.

Why this is a real bug and not polish: **silence means APPROVE in this mode.** A prompt nobody can
see approves the very request it was supposed to ask about. An invisible card is a security defect
that happens to look like a UI defect.

A safety net forces the card visible if the fade-in has not completed, and the log says it had to
be forced. Four probes at t0/t100/t400/t2000 record alpha, isShown, visibility, size, and
`getGlobalVisibleRect`.

## 0.9.0 — no animators; the card and its countdown, driven by a clock
`869701b` · 2026-10-17

Three defects, one root cause, and it took `/logs` to see it:

```
notify[5001] t100  alpha=0.0 isShown=true onScreen=true rect=Rect(0,0-435,86)
notify[5001] fade-in STALLED alpha=0.0 -- forcing visible
```

Correctly sized, on screen, in the right place — and **the fade-in NEVER RAN**. Alpha sat at 0.0
through 100ms of a 180ms fade. The 0.8.0 safety net is the only reason anything was visible at
all, which is why Mido's next report was *"I see it, much better but we lost the progress"*.

Root cause, finally stated plainly: the card's entire appearance depended on animators actually
running, and on this device they do not run reliably. **Every failure was silent.** An animator
that never fires looks exactly like one that finished.

That is not cosmetic: silence means APPROVE, so three releases of "1ms flash" were three releases
of requests being auto-approved behind an invisible prompt.

The fix removes the dependency. **One main-thread tick, ~30fps**, reads
`System.currentTimeMillis()` and sets everything directly:

- fade-in alpha computed from elapsed time, not animated
- countdown `level` computed from **remaining** time (deadline − now), so a missed tick cannot
  make it drift
- fade-out and removal in the same tick

Two supporting changes from evidence: the drain is a `ClipDrawable`, not a layer inset
(`setLayerInsetRight` mutated bounds without reliably repainting — the frozen bar); and removal is
owned by the tick, so an animator that never fires cannot strand a card forever.

A `midpoint level=` log line proves the countdown moved rather than asking anyone to eyeball it.

## 0.10.0 — verb badge on the NOTIFY card
`cde6d77` · 2026-10-17

The badge: a small coloured tag reading `read`/`write`/`edit`/`del`/`exec`, authored from the
route's verb, never inferred from the HTTP method. Coloured by **consequence**:

- `read` green — nothing changes
- `write`/`edit` amber — something changes
- `del` red — something is destroyed
- `exec` amber — deliberately middle, since it covers both "send a message" and "act on a
  notification"

Light fills with near-black text, because the card is dark. The badge hugs its text
(`WRAP_CONTENT`, 5dp padding, 4dp corner) — a stretched badge would read as a header bar.

Padding 12/8/6/8 → 8/6/4/6, card 232dp → 216dp. The verb is threaded through the gate rather than
looked up on the card: the UI renders, it does not compute.

## 0.11.0 — error codes are total, and a 200 that was really a failure
`22f1dec` · 2026-10-17

Two bugs, both from one device log after Mido cancelled a prompt. The `403` was good news:
`NOTIFY_REJECTED` captured on device for the first time (`logs.read 403 1903ms`, card log
`settle reason=TAPPED shownMs=1850`).

But right before it, two records:

```
HANDLER_FAILED logs.read 500 2
HANDLER_FAILED logs.read 500 2
```

My own bad input: `/logs?lines=600` (cap 500), `bad_lines` thrown correctly — then mapped to
`500`, because it had no entry in `httpStatusFor`. A caller's typo was recorded as a **server
fault**, and `HANDLER_FAILED` is deliberately not noise, so two fake incidents landed in the log.

Fix 1: `ErrorCodes` — every code in one total map, pure Kotlin. Enforced rather than hoped for:
`ErrorCodesTest` walks the source tree, finds every `PluginError("...")` literal, and fails the
build if any code is missing. It also pins the direction: caller-fault codes are `4xx`, our-fault
`5xx`, unknown is `500` and never `4xx`.

Fix 2, found while writing fix 1: `statusOf(Int)` ended with `?: Response.Status.OK` — an unmapped
status silently became **200 OK**. A failure reported as success is the worst direction, and it had
sat there since R1. Unknown now answers `500`.

**73 tests.** `ErrorCodesTest` caught a real thing during this release: its first version called
`android.util.Log`, not mocked in JVM tests. The alarm moved behind a hook the app wires at
startup. A guard that cannot run is not a guard.

## 0.12.0 — NOTIFY is the floor; every auto-approve path removed
`df972bd` · 2026-10-17

Mido: *"you know what, I like notification mode, let's remove silent from the whole app
everywhere, notify is the minimum."* He had asked for a per-pair `silent` option, then reversed
it — so instead of **adding** a fourth mode, this **removes** the one auto-approve path.

Gone: `GlobalMode.OFF` (switch is now `AUTOMATIC | SECURE | NOTIFY`), `Mode.OFF`, the
unattended-approve branch (`isAttended` is now unconditional), the OFF confirmation dialog and the
persistent red banner.

**The invariant this buys: the switch can only move toward asking more.** No value and no
configuration can skip a human.

Two things deliberately kept:

- `AuditOutcome.OFF_APPROVED` survives, read-only — deleting it would turn honest history into
  apparent corruption. Never rewrite the log.
- A legacy `"OFF"` in `policy.json` loads as `NOTIFY`. A file written by ≤0.11.0 must not brick
  the app, and there is one safe direction: `OFF` meant "no gate", so it maps to the floor that
  still asks a human.

New test: **`no switch value can weaken a request below NOTIFY`** — loops `GlobalMode.values()`
and fails if any resolves below `NOTIFY`, so reintroducing an auto-approve value fails the build
until someone reopens the question on purpose.

**76 tests.**

## 0.12.1 — end-of-fade flash: remove+promote atomically
`dce196e` · 2026-10-18

Attempted the flash as a remove/promote handoff race. **The device disproved it** — see 0.12.2.

## 0.12.2 — the flash was window alpha; fade the window, not just the view
`1b8514a` · 2026-10-18

Mido: *"weird flashing near the notification end after the fade-out ends"*, then after the 0.12.1
fix: *"same, if fades out to 0, then a flash of full opacity card appears for a moment before card
disappears"* — so the remove/promote ordering was **not** the cause.

**Real cause:** the fade-out drove `View.alpha` while the **window's** own alpha stayed 1. The
compositor uses the window alpha, so the instant `removeView` destroyed the surface, one frame of
the full-opacity window buffer re-composited — "fade to 0, flash, gone".

Fix: drive `WindowManager.LayoutParams.alpha` through `updateViewLayout`, plus a ~3-frame commit
grace at alpha 0 before removal. A `teardown windowAlpha=0.0 viewAlpha=1.0` line logs it, so the
log proves the window reached 0 rather than asking the eye to be the instrument.

Three flash families now exist and must not be conflated: animator dependence (0.9.0), the
`MATCH_PARENT` child (0.6.2), and window alpha at teardown (0.12.2).

## 0.13.0 — timeZone is required on every calendar call, validated
`f1aec18` · 2026-10-18

Built because a live read exposed a silent-zone bug: an Oct 18–20 window (`+02:00`) returned an
event dated **2026-10-06**, and boundary probes proved the window was interpreted in the
**calendar's** zone (`Asia/Kuala_Lumpur`).

`timeZone` is now required **and validated** (real `ZoneId`, else `400 bad_timezone`) on the read
and on create/update. `quickAdd` requires it **and must match the calendar's** (Google's
`quickAdd` takes no zone at all, so accepting a different one would be the same silent
reinterpretation). `delete`/`move` take none.

**81 tests** (`TimeZonesTest` +5).

## 0.13.1 — remove legacy OFF shims
`0e26486` · 2026-10-18

Under the standing no-backward-compat rule (not live yet), the shims 0.12.0 carried are deleted:
`AuditOutcome.OFF_APPROVED` from the enum, and the `"OFF" → NOTIFY` load from `Policy.parse`.

Consequence, accepted on purpose: a pre-0.12.0 record carrying `OFF_APPROVED` now decodes as an
*unreadable line*, and a `policy.json` carrying `"OFF"` now **throws**.

## Plan — SECRET-BROKER-PLAN
`3ff8bf1` · 2026-10-18

The generic secret-broker design: a third-party API secret lives on the phone, Keystore-encrypted
and biometric-gated, never on the host, without a Kotlin plugin per service.

## Plan — D7/D8: one plugin per secret, fixed EXECUTE verb
`5861b89` · 2026-10-18 · `dd8e836` · 2026-10-18

D7: a plugin per secret, verb from the HTTP method (not the path). D8: one route per secret, fixed
`EXECUTE` verb (no verb coupling). Q6 answered.

## 0.13.2 — `specialUse` FGS + battery-optimization exemption
`880a04e` · 2026-10-18

Android 15 (targetSdk 35) caps `dataSync` foreground services at 6h/24h and then throws
`ForegroundServiceDidNotStopInTimeException`, killing the process. **That was the "random"
crash**: `BridgeService` declared `dataSync`, ran 24/7, and never overrode `onTimeout()`.

- manifest: `dataSync → specialUse` (with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`); swap
  `FOREGROUND_SERVICE_DATA_SYNC` for `FOREGROUND_SERVICE_SPECIAL_USE`.
- `BridgeService`: `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+, `onTimeout → stopSelf()` as a
  safety net regardless of type.
- `MainActivity`: battery-optimization row in the setup checklist.
- `gradle.properties`: `kotlin.compiler.execution.strategy=in-process` — this box is RAM-tight and
  a forked Kotlin daemon fails to connect under pressure.

## Plan — close out SECRET-BROKER-PLAN
`f6a2e28` · 2026-10-18

Session unlock (D9), resolved edges, error codes.

## 0.14.0 — the secret broker: vault, generic broker, CRUD screen
`5baa30c` · 2026-10-18

The generic secret broker (Todoist first), so a secret lives on the phone and never on the host.

- `core/SecretVault`: **envelope encryption**. VK (Keystore, auth-per-use, `BIOMETRIC_STRONG`,
  StrongBox with TEE fallback) wraps a random DK that does all secret crypto. One fingerprint per
  **session**, not per secret — the platform demands auth for encryption too, so a per-secret
  auth-per-use key would have cost a prompt per write.
- `plugins/secrets/SecretBroker`: injects the `***` placeholder, refuses redirects, boundary-correct
  host match, blocks loopback/private ranges, scrubs the secret from headers **and** body.
- `plugins/secrets/SecretPlugin`: one plugin per secret, `POST /secrets/{name}/call`, fixed
  `EXECUTE` verb.
- `plugins/secrets/HostAllowlist`: exact or `*.domain`.
- `VaultGate`: the load prompt carries the vault `CryptoObject`, so one scan is both app lock and
  vault unlock.
- `SecretsActivity`: CRUD with `FLAG_SECURE`, password-style input, autofill off, reveal-on-demand,
  never logs a value.
- `ErrorCodes`: 11 new codes.

**81 tests.**

## 0.14.1 — two links per secret (docs + key page)
`cf9b203` · 2026-10-18

Each secret carries two non-secret links: where the API is documented, and where you issue the
key. Plaintext index (`vault_meta`), because they are pointers, not credentials — so they stay
readable while the vault is locked, which is exactly when you want them.

Editing a link needs **no fingerprint** — demanding one would be theatre. `https`-only on write.
Reads the old bare-array index too, so a 0.14.0 vault keeps its names.

## 0.15.0 — per-secret hosts + crypto-bound approvals
`af1d49e` · 2026-10-18

Two decisions from chat.

1. **Decision A — every approval scan is now `CryptoObject`-bound to the vault key.**
   `promptBiometric` prepares the vault cipher first and passes it as `CryptoObject`; the
   authorised cipher unwraps the vault when locked, so **one modal unlocks AND approves**. Falls
   back to the plain prompt only before the vault exists. Fails closed when the key is invalidated.
   Accepted cost: a crypto-bound prompt cannot accept `DEVICE_CREDENTIAL`, so approvals become
   **fingerprint-only** once a vault exists.
2. **Allowed hosts moved from a GLOBAL list onto each secret.** The global list meant the `todoist`
   secret could be sent to any host some *other* secret had approved. `HostAllowlist` is now pure
   (`allows`/`requireRule`), no `Context`, unit-testable.

**81 tests.**

## 0.15.1 — fix vault unlock: store the DK wrap IV
`d86a73b` · 2026-10-18

Bug: *"tap to unlock"* did nothing. Log:
`vault unlock not completed: could not prepare the vault cipher: IV required when decrypting.`

Two defects in the envelope key-wrap: `prepareUnlockCipher` never passed an IV to
`Cipher.init(DECRYPT_MODE, …)`, **and** `initialize()` wrapped the DK but never **stored** the IV
it generated. Since GCM uses a random IV per wrap, that made every 0.14.x vault **permanently
unopenable** — the ciphertext is there, the IV needed to read it is not.

Fixes: `F_DK_IV` stores the wrap IV beside the wrapped DK; `isUnrecoverable()` detects a 0.14.x
vault and offers a reset that says plainly the secrets cannot be recovered (a silent fresh vault
would look like success while discarding a credential). New code `vault_unrecoverable`.

**89 tests** (`HostAllowlistTest` +8).

## 0.15.2 — hosts in the add/replace dialog
`4e55c91` · 2026-10-18

Merge the secret and its API domains into **one** dialog instead of a separate host list.

- `SecretsActivity.addSecretDialog`: new "allowed hosts" field, comma/space separated, prefilled
  from what is stored so a replace does not silently drop the rules.
- `SecretVault.put`: `hosts` param (`null` = keep existing, list = replace). Validated before any
  write, so a typo in the last field cannot half-update the secret.

**Verified end to end on the phone:** `POST /secrets/todoist/call` with `Authorization: Bearer ***`
returned `200` with the real task list, token scrubbed.

## 0.16.0 — `GET /secrets`, read a secret's non-secret data
`ad69c41` · 2026-10-18

Requested explicitly: fetch a secret's links through a proper route, not a field bolted onto
`/debug`.

New `SecretIndexPlugin`, registered once: `GET /secrets` and `GET /secrets/{name}`. Returns name,
docs, key, hosts, callRoute. Deliberately does **not** require an unlocked vault — names/links/hosts
are plaintext by design. The **value** is never returned and no parameter can ask for it.

No route collision: calls are `POST /secrets/{name}/call` (3 segments), the index is 1–2 segments.

## 0.16.1 — fix 204: an empty success is not an unsupported type
`e6a1313` · 2026-10-18

Mido: *"don't do shitty workarounds, fix the 204."* Correct — the `/sync` detour was a workaround.

`POST /tasks/{id}/close` and `DELETE /tasks/{id}` answer `204 No Content` with
`Content-Type: text/html`. The broker refused anything non-JSON as `unsupported_content_type`, so
completing or deleting a Todoist task was unreachable.

Root cause was conceptual: the rule conflated "I cannot read this payload" with "this payload is
empty". A 204 is a **success** with nothing to say.

Fix — the decision is now a pure function, `ResponseBody.classify(status, body)`:

- a 2xx with a blank body → `EMPTY_SUCCESS`, **Content-Type ignored**;
- otherwise the **body** decides, not the header (parses as a JSON object → `JSON`, else
  `UNSUPPORTED`);
- a non-2xx with an empty body is still `UNSUPPORTED`.

It also stops trusting `Content-Type` for non-empty bodies, so a server mislabelling real JSON as
`text/plain` no longer breaks. The refusal for a genuinely non-empty non-JSON body (PDF/image/zip/
HTML, or a bare scalar/array) stays.

Extracted for the same reason `HostAllowlist` was: it lived inside an `HttpURLConnection` call and
was untestable, and the untested branch was the wrong one. `ResponseBodyTest`: 9 cases.

**98 tests.**

## 0.17.0 — design + AI release (build 93)
`b076cc8` → `9d39a6f` · 2026-10-19 (overnight run)

A deep UI/AI pass, built as separate commits for easy revert. Every one a green build.

- `b076cc8` **Material 3 foundation.** `material:1.12.0`; `ui/Theme.kt` as the single source of
  design truth (semantic colours, dp metrics, ≥48dp targets, rippled surfaces), replacing 24
  scattered hex literals and the pixel-radius `pill()` helper.
- `bf43d1e` **Migrate every screen to the Theme; delete `UiKit`.** Five duplicated button helpers
  gone. Fixed the hero-card bug: the state colour was set per branch then overwritten with
  `NEUTRAL_BG` two lines later, so it was always grey. Buttons tint via `backgroundTintList`,
  preserving the ripple.
- `f02c353` **1s decision arm delay + arming progress bar** — the wait is visible instead of a
  dead-looking disabled button. Driven by a handler tick (no animator dependency).
- `6734216` **Alert sounds + haptics.** `SECURE` rides the approval channel (notification-class,
  respects silent/DND) plus a short double-tap vibration; `NOTIFY` plays one quiet tick, gated on
  `isAttended()`.
- `530c57d` **Summariser seam.** `ai/SummaryModel.kt` (never throws, returns null on failure),
  `HeuristicSummarizer` (the honest baseline), `MediaPipeGemma`, `ModelRegistry` (one constant
  names the model file).
- `ba7309e` **Wire summary + risk into the SECURE modal** (display-only).
- `69dcf4e` **Fetch-first NOTIFY card, tap-to-approve, AI line + risk tint.** The 5s clock starts
  only after the fetch lands; a tap approves and skips the wait; drag slop 8dp → 16dp (a tap now
  approves, so the threshold is security-relevant).
- `8dea3e5` **Heuristic risk tests** (98 → 107).
- `9d39a6f` **Version 0.17.0 / build 93 + `VIBRATE` permission** — without it every haptic written
  this release would have no-opped silently.
- `a1b3ecd` **Build modal rows once** — they were built twice per request, costing a second live
  Google round-trip for a pre-fetching route.

## 0.18.0 — migrate the summariser to LiteRT-LM (build 94)
`8525b43` · 2026-10-19

MediaPipe `tasks-genai` is maintenance-only; Google's replacement is LiteRT-LM. Swap the runtime,
keep the seam.

- `tasks-genai:0.10.35 → litertlm-android:0.18.0`; new `ai/LiteRtLm.kt` (Engine/Conversation API),
  `MediaPipeGemma.kt` deleted.
- **Model choice forced by gating, not taste:** every small Gemma in `litert-community` is gated
  (401 without an HF token, which this box has none of), so an ungated `Qwen3-0.6B` mixed-int4
  shipped instead.
- Toolchain: the artifact carries Kotlin 2.4.0 metadata, so the plugin moved 2.0.21 → 2.4.21 and
  `kotlinOptions` moved to `kotlin { compilerOptions { } }`. That stricter compiler flagged a real
  redundant `.toString()` on `Person.uri`.

**107 tests.** Version 0.18.0 / build 94.

## 0.19.0 — the AI is core; the app self-provisions the model (build 95)
`88501ce` · 2026-10-19

Mido: *"the app should be able to download the model if not found"* and *"it shouldn't be able to
work without the model. treat it as core functionality, not a nice to have."* Decision A: the
bridge is **not up** without it.

- **Self-provision** (`ai/ModelProvisioner.kt`): fetch the `.litertlm` once from Hugging Face,
  resumable (HTTP `Range`), staged to `<name>.part` and renamed onto the real path only after size
  **and** sha256 match — a truncated download can never be mistaken for a model.
- **Fail-fast:** readiness now **gates** the bridge. Every route answers `503 model_not_ready`
  until the model is present. `/debug` and `/logs` are exempt, because they are how a not-ready
  bridge is diagnosed.
- **The heuristic is GONE** — deleted with its test. It was the silent fallback the fail-hard rule
  forbids. `aiSummary` now throws `ai_unavailable` (500), and the NOTIFY card is withdrawn rather
  than shown undescribed and silently approvable.
- **Warm + release:** `BridgeService.onCreate` warms the engine; `onTrimMemory` releases it —
  deliberately with **no level comparison**, since `TRIM_MEMORY_RUNNING_LOW` is deprecated as of
  API 34/35 (apps are no longer notified of it).

**112 tests.** Version 0.19.0 / build 95.

## 0.20.0 — per-route AI opt-out, ANY verb in rules, re-download guard (build 96)
`6a22f8f` · 2026-10-19

Three fixes from Mido's first device pass.

1. *"the model downloaded, I touched it it started downloading again"* — `provision` is now
   idempotent; the home screen confirms before a re-download. A tap on a ready row was silently
   starting a 474 MB fetch.
2. *"debug call waited for the model"* — **the real bug.** `/debug` was exempt from the readiness
   gate but still ran inference, so with a broken model the diagnostic route answered
   `ai_unavailable` and the one path that explains why was gone. Fixed at the source: a `Route`
   declares `aiSummary: Boolean = true`, and that **one** flag is both the AI opt-out and the
   readiness exemption, so the two cannot drift.
3. *"in the rules verb selection allow ANY"* — `Rule.verb` is now nullable; null means ANY. A
   **bounded** reintroduction of precedence: one axis, two levels (an exact verb rule wins over the
   plugin's ANY rule; plugin stays exact; precedence is order-independent). A **missing** verb is
   still a parse error — never a silent wildcard.

**122 tests.** Version 0.20.0 / build 96.

## 0.20.1 — model loader is a button-scoped progress fill (build 97)
`ab74500` · 2026-10-19

Mido: *"tabbing download again still shows the message"* and *"the loader should be button scoped,
using a bg color progress fill style."*

- `Theme.progressButton`: a MaterialButton whose **own** background is a two-layer drawable
  (dimmed track + `ClipDrawable` fill) drained by `level`. Built from `background` rather than
  `backgroundTintList`, which costs the ripple while loading — correct, because the button is
  disabled mid-download (a second writer on one `.part` file corrupts the model).
- The fill is driven by the real byte count, never a timer.
- The `"Downloading the AI model..."` toast is gone — since `provision` became idempotent it was a
  **lie** on every no-op tap. The result toast now carries the provisioner's own detail.

Version 0.20.1 / build 97.

## 0.20.2 — swap the model artifact (build 98)
`25512ff` · 2026-10-19

The real cause of "the model is not summarising" was **not** memory, GPU or speed. The phone's own
log named it:

```
Failed to apply template: invalid operation: tried to use + operator on
unsupported types string and sequence (in template:14)
```

The mixed-int4 Qwen3 artifact's chat template concatenates `'\n' + content` — valid for a string
but fatal when content arrives as a list of parts, which is what `Contents` always serialises to.
Every inference failed, and because the AI is core the whole bridge went down with it. Reproduced
with the official Python binding, so it was the model file.

Swapped to `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm` (344,671,744 bytes, sha256 `03e7da1e…`),
verified by hand to accept a system instruction and return valid JSON on READ, DELETE and EXECUTE.
**The system prompt stays and is now longer** — it is what the old template choked on, so keeping
it is what the swap proves works.

Also removed constrained decoding (`ResponseFormat.json`): with `LL_GUIDANCE` this model truncates
mid-object (a real run returned the literal `{"description":"}`), while the same prompt
unconstrained produced valid JSON 3/3.

Version 0.20.2 / build 98.

## 0.21.0 — queued fixes: row spacing, auto-start server (build 99)
`1a9a8c2` · 2026-10-19

Both from Mido's device pass, queued per his workflow rather than fixed mid-test.

1. *"add margin around list items they are touching"* — `Theme.ROW_GAP_DP` (8dp) is now one
   constant applied inside `Theme.statusRow`, and reused by Settings/Log rows. That drift is how a
   UI starts looking assembled rather than designed.
2. *"start server on app load automatically"* — `maybeAutoStartServer()` guarded twice: **once per
   process** (so a manual STOP stays stopped — otherwise STOP would be impossible to keep) and
   **never over a running server**.

Version 0.21.0 / build 99.

## 0.21.1 — fix GPU, and fail hard: no CPU fallback (build 100)
`dca3ee4` · 2026-10-19

Mido: *"no, fix gpu"* and *"I said fail hard a million times. only the best most efficient
performance for my phone is accepted."*

The GPU was never broken — the app could not **see** the vendor OpenCL driver, because an Android
app cannot `dlopen` `libOpenCL.so` without declaring it:

```xml
<uses-native-library android:name="libOpenCL.so" android:required="false" />
<uses-native-library android:name="libvndksupport.so" android:required="false" />
```

The engine loaded and logged `engine on GPU` regardless, then failed every inference with
`Can not find OpenCL library on this device` — a load-time success with a run-time failure, which
is why it read as a slow model. `required="false"` so a device without a vendor driver still
installs.

**And the CPU fallback added an hour earlier is REMOVED** — exactly the silent degradation this
project forbids. Now `createEngine` builds the GPU and nothing else; `warm()` **proves** the
backend with a throwaway inference (loading is not proof); no retry, no demotion. The reason is
kept in `LiteRtLm.lastEngineError` so `/debug` can name the cause.

Version 0.21.1 / build 100.

## 0.21.2 — SECURE budget 3s → 10s (build 101)
`31477c4` · 2026-10-19

Measured on device, twice: the 0.6B model on the S22 Ultra's GPU infers a summary in ~3.2s
(3194ms, 3176ms). The SECURE budget was 3s, so every answer was discarded as "too late" and the AI
looked broken when it was merely 0.2s over. Mido: *"make it 10 seconds."*

The budget is **not a performance target** — it is the point at which a late answer stops being
worth having. 10s clears the measured cost with room for a colder GPU. `NOTIFY` was already 10s,
so the two paths now agree.

Version 0.21.2 / build 101.

## 0.21.3 — bundle the GPU sampler .so (build 102)
`0ee2bb0` · 2026-10-19

`sampler_factory` `dlopen`s `libLiteRtTopKOpenClSampler.so` for GPU sampling — and that library is
**not** in `litertlm-android`'s AAR, which ships only `liblitertlm_jni.so`. So the factory fell back
to the CPU-side sampler:

```
OpenCL sampler not available, falling back to statically linked C API
```

That fallback is not free: with a CPU sampler the GPU hands logits back for **every generated
token**. That is the 3.2s.

Fix: bundle the v0.18.0-tagged library at `app/src/main/jniLibs/arm64-v8a/libLiteRtTopKOpenClSampler.so`
(sha256 `0f77c888…`, verified against the LFS pointer for that tag — **not** `main`, since it is a
`dlopen`ed C ABI that must match the runtime version). Self-contained: `readelf` shows only system
deps.

Version 0.21.3 / build 102.

## 0.22.0 — the modal arms on the fetch, not a timer (build 103)
`ce15b9b` · 2026-10-19

Mido: *"change the static waiting mechanism for modal activation, instead use whatever time needed
to enable the buttons"* + *"fetch alone"* + *"60 at enable"* + *"add fake loading feeling, claude
style some word like thinking... wondering..."* + *"if AI fails show error but unlock modal for
user."*

The modal used to be built **after** the summary, so a request waited up to 10s for nothing and
then another 1s of dead buttons — two static waits stacked. Now the modal appears **immediately**
with the deterministic rows, and the gate fetches in the background.

- `ApprovalGate` owns the fetch, not `BridgeService` — only the gate knows when a request actually
  becomes the one on screen.
- `ApprovalGate.AiState { Loading | Ready | Failed | NotNeeded }` is read **live** by the modal
  each tick.
- `DECISION_DELAY_MS` is **DELETED**. The only enable trigger is `enabledAtMs > 0`.
- **The 60s clock starts at enable**, not at show, so model latency is never charged to the
  human's decision time. `Pending.maxWaitMs()` derives the server's total wait.
- **DENY is enabled immediately and independently** — denying is fail-closed.
- The loading state cycles a word + dots; the indicator is **indeterminate** on purpose (inference
  is a blocking call with no token stream, so a determinate fill would be an invented number).
- **AI failure unlocks the modal and shows the reason.** Not a fail-hard weakening: a **missing**
  model still refuses the whole bridge with `503` before any modal exists.

Version 0.22.0 / build 103.

## Housekeeping — snapshot before factchecker prompt change
`bf2a7e1` · 2026-10-19

Snapshot only.

## 0.23.0 — modal UI pass: insets, bigger summary, tighter rest (build 104)
`8e03c4c` · 2026-10-19

Mido: *"improve modal ui / Add top padding so that when I get a notification with the modal it
doesn't overlap / And make the ui summary more concise and larger / the rest of the modal data gets
smaller / add margins between the buttons."*

1. **System-bar insets.** The modal drew under the status bar and nav bar. Now
   `ViewCompat.setOnApplyWindowInsetsListener` on the root pads by `systemBars().top/bottom` —
   insets, not a fixed dp, since the bar height varies per device and orientation.
2. **The summary is now the largest thing on the screen:** 26sp bold (was 17sp), bounded to 3 lines
   and ellipsized rather than trimmed in code.
3. **Everything else shrank**, so the summary dominates by contrast (title 22→20, WHAT 20→16, row
   values 16→13, etc.).
4. **12dp between DENY and APPROVE**, so the two decisions are never one fat-finger apart.

Also tightened the model prompt to produce shorter text (`"max 8 words"`, "a headline, not a
sentence", no filler).

Version 0.23.0 / build 104.

## 0.23.1 — soften the summary word cap into a suggestion (build 105)
`0a4166c` · 2026-10-19

Mido: *"remove the hard cap 8 words, or make it a suggestion instead."*

A hard ceiling makes a small model either contort the sentence or drop the very noun that matters,
and a truncated summary is worse than a slightly long one — the human reads it as the whole truth.
Now: `"<brief, ideally under ~8 words>"` with an explicit escape hatch. Length is **guided by the
prompt** and **bounded by the renderer** (3 lines, ellipsized), which degrades visibly rather than
silently.

Version 0.23.1 / build 105.

## 0.23.2 — arm64-only ABI (build 106)
`4b6b4f0` · 2026-10-19

Two things, both from Mido's 2026-10-19 messages.

1. The word-cap softening (above) landed here too.
2. **`abiFilters = arm64-v8a` ONLY.** The litertlm AAR ships native libs for **both** `arm64-v8a`
   and `x86_64`, and both were being packaged: 25.1 MB of `lib/x86_64/liblitertlm_jni.so` that can
   never load on the target phone, on every APK — 36% of the download. This is what made the 0.23.x
   Taildrop transfers stall: two 150s timeouts, then a background run that moved ~10 MB of 69 MB
   and froze (tx counter static across three samples).

   **APK: 69 MB → 42.7 MB.** The target is a Samsung S22 Ultra (arm64-v8a), sideloaded by hand, so
   `x86_64` has no use here. If an emulator is ever needed, add it back deliberately — do **not**
   delete this filter to "fix" an x86_64 install and ship the dead weight to the phone again.

**122 tests green.** Version 0.23.2 / build 106. APK taildropped, exit 0.

## 0.24.0 — the modal's third button, and "PER-PAIR RULES" → "EXCEPTIONS" (build 107)
`e89de3e` · 2026-10-19

Mido: *"in the modal I want a third button to automatically add an exception for the current
request. using our same system for per-pair rules, and change this stupid name while at it"*.

**The rename.** The rules screen was `PER-PAIR RULES`, which named the *implementation*. It is now
`EXCEPTIONS` — one word for one concept, and it explains itself, because the default **is** "ask"
(`AUTOMATIC` with no rule → `SECURE`), so every rule is literally an exception to it.

**The button.** A third decision in the `SECURE` modal: it approves the request **and** writes an
exception for its pair in one tap. Details that matter:

- **Its own full-width row above DENY/APPROVE, not a third column in that pair.** The 0.23.0 pass
  put 12dp between DENY and APPROVE precisely so the two decisions are never one fat-finger apart;
  wedging a third button between them would undo it. Secondary style, so a reflexive tap meant for
  APPROVE cannot land here.
- **The label names the behaviour the tap produces** — `ALWAYS NOTIFY` / `ALWAYS ASK` — because the
  exception is the *other* mode, computed by the new pure `exceptionModeFor(resolved)`.
- **Scoped to `AUTOMATIC` — and ONLY `AUTOMATIC`.** `resolve()` reads the rule table in that mode
  alone, so under a forced `SECURE`/`NOTIFY` a written rule is inert. Mido's call (2026-10-19):
  *"just add it, it should work with automatic and that's it"* — so the button is **absent**, not
  disabled, outside `AUTOMATIC`. No flipping the switch behind the human's back, no button that
  lies about what it just did.
- **The floor holds.** `Rule.ALLOWED_MODES` is `NOTIFY | SECURE`, so an exception can at most make a
  pair *quiet*. It can never auto-approve, and no tap here can create a path that skips a human.
- **The Activity never writes policy.** It renders the offer and hands the finished `Rule` back;
  `BridgeService.writeException` persists it — the single place policy is written, read-modify-write
  (`withRule`, never a fresh `Policy`), audited with `source = "modal"` so the log says *where* a
  policy decision came from. A failed write does not fail the request (the human did approve) but is
  surfaced through the audit-health banner, never swallowed.
- **Same biometric gate as APPROVE** — the tap approves a request, so it clears the same finger,
  and arms on the same signal (never before the summary the human is meant to read).
- `GateResult.ApprovedWithException` carries the rule back. It is checked **before** plain
  `Approved` in the gate, because the exception tap also sets `approved = true` — testing approval
  first would silently downgrade it and lose the rule the human asked for.

Planned in `EXCEPTIONS-PLAN.md`; the open questions there (Q1/Q2/Q3/Q5/Q6) were settled in chat as:
set-and-approve, exact verb only, `ALWAYS …` labels, own row above the pair, shared arm delay.

**126 tests green** (4 new: the other-mode mapping, the file round-trip, siblings untouched, exact
beats ANY). Version 0.24.0 / build 107.

## 0.24.1 — the exception button never rendered on calendar/gmail (build 108)
`2aa39ab` · 2026-10-19

Found by testing on the device, and it is the "computed then discarded" trap this project has
already paid for twice.

`exceptionOffer` was built at the call site and passed to **two of the three**
`ApprovalView(...)` returns. The generic branch — the one calendar and gmail use — took the
constructor default of `null`, so the button silently did not exist on exactly the routes that
went through it. Notifications worked, which is why it looked fine.

**Fixed by passing it in the third branch, and by adding `ApprovalViewWiringTest`: a source scan
that counts `ApprovalGate.ApprovalView(` constructions against `exceptionOffer = exceptionOffer`
pass-throughs and fails the build when they disagree.** The regex is anchored to end-of-line on
purpose — the call site writes `exceptionOffer = exceptionOfferFor(...)`, and an unanchored match
counts it and fails on a correct tree (that happened on the first run).

The guard was **proven to fail**: breaking one pass-through produced
"Found 3 construction(s) but 2 offer pass-through(s)".

## 0.24.2 — a settled NOTIFY card could not be dismissed (build 109)
`2aa39ab` · 2026-10-19

Reported on device: *"I have a notification card still stuck!"*, then *"it doesn't even dismiss on
x click"*.

Both symptoms, one cause: **removal was not guaranteed.** `settle()` marks the entry `settled`
(so the outcome is reported and every tap path early-returns), then starts a fade-out as a chain
of posted runnables. If any link in that chain never ran, the card stayed on screen and already
`settled` — so the card body ignored taps, and the `x` called `settle()` again, which
early-returned. A card with every control dead, and no code path left that would remove it.

This is the same class of defect the overlay has produced before (animator-dependence in 0.9.0):
**appearance and lifecycle must not hang on a chain that can silently stop.**

Three changes, all pointing at one idea — removal gets its own guaranteed path:

- **`Entry.removed` is separate from `Entry.settled`.** Settled means "the outcome is decided";
  removed means "the window is gone". Conflating them is what made a stuck card unreachable.
- **`teardown(entry)` is idempotent and unconditional**, reachable from four places: fade
  completion, the settle backstop, the `x`, and `dismissAll()`. It is deliberately NOT guarded by
  `settled` — a settled card still on screen is the bug.
- **A backstop fires at `FADE_OUT_MS + COMMIT_FRAMES_MS + BACKSTOP_SLACK_MS`** and tears the
  window down whether or not the fade got there; and a throw inside the fade falls straight to
  teardown instead of dying quietly. The `x` now calls `settle()` **and** `teardown()`.

`dismissAll()` uses the same path, so service shutdown cannot race a fade and both remove one
window.

127 tests green. Version 0.24.2 / build 109.

## 0.24.3 — the exception button killed the modal before the fingerprint (build 110)
`31b727f` · 2026-10-19

Reported on device: *"I clicked always notify like 5 times, modal kept coming back, only when I
clicked approve I got a fingerprint"*.

The report contained the diagnosis. **No fingerprint and the modal returning mean the request was
never resolved** — a prompt that never appears means the Activity died before it could show one.

The handler called `finish()` **unconditionally, immediately after** starting the biometric
prompt:

```kotlin
if (requiresBiometric) {
    promptBiometric { ApprovalGate.resolveWithException(id, off.rule) }
} else {
    ApprovalGate.resolveWithException(id, off.rule)
}
finish()          // <-- runs at once, before the async prompt can appear
```

`promptBiometric` is **asynchronous**: it shows the system prompt and returns immediately, and the
resolve happens later in its callback. So `finish()` destroyed the Activity first — the callback
never fired, the request was never resolved, and the gate re-posted the modal. Five taps, five
dead ends.

`APPROVE` never had this bug because its `finishWith()` lives **inside** the callback. The fix
mirrors that exactly: resolve, then finish, both in the success callback; on a dismissed or failed
prompt nothing happens at all, so the request stays pending and can be retried.

**The durable rule: any code that starts an ASYNC confirmation must put its completion (and its
`finish()`) inside that confirmation's callback — never on the line after the call.** `finish()`
after `promptBiometric(...)` is a no-op against an Activity that is about to be destroyed; the
prompt is a system surface and cannot outlive its host.

127 tests green. Version 0.24.3 / build 110.

## 0.25.0 — policy rules are readable over HTTP; FSI state is observable (build 111)
`6b95325` · 2026-10-19

Mido: *"test, and add policy rules to logs but read only"*.

**`/debug` now reports the rules.** `policy` was `{global, readable}` and nothing else, so a
`plugin × verb` decision could not be inspected from outside — every rules question meant the
human reading the screen aloud, which is exactly what this endpoint exists to prevent.

```
policy: {
  global, readable, ruleCount,
  rules: [ { plugin, verb, mode, key } ]
}
```

`verb` carries the literal `"ANY"` (matching the on-disk format and `Rule.key`), so a reader
never interprets a null; `key` is the same `plugin/VERB` string an audit record's `rule` field
carries, so a log line and this report compare without hand-reconstruction.

**Read-only by construction.** No write route, and no parameter here can change anything. Policy
is written in exactly two places — the EXCEPTIONS screen and the modal's exception button — and
both audit it. A diagnostic surface that could mutate the gate would be a security hole, not a
feature. That constraint is stated in the code so a future change has to argue with it.

**`permissions` block added** (`fullScreenIntent`, `drawOverApps`, `notifications`), because the
FSI question needed to be observable rather than guessed. `fullScreenIntent` reports **JSON null
below API 34** rather than `false` — "not required" and "revoked" are different facts.

Why this matters beyond diagnostics: `USE_FULL_SCREEN_INTENT` is a **special app access** on
Android 14+, not a runtime permission — its default is re-derived from the app's category when the
package is added, and the user's own grant is what keeps it on. That is the platform rule, and it
is why this permission deserves a report field rather than a guess. The gate also has a second
delivery path (`ctx.startActivity`, legal via draw-over-apps), so whether FSI is actually
load-bearing is now a question the report can answer.

**Measured, correcting an earlier claim in this file:** installing 0.25.0 over 0.24.x did **NOT**
revoke it — `permissions.fullScreenIntent` read `true` immediately after the update. So the
"every update revokes it" theory is **not supported by the evidence on this device**. The Play
Store's automatic revocation applies to Play-installed apps; these are sideloaded, and the app-op
survived. Treat a lost grant as something to investigate (a reinstall under a different signing
key, a Settings toggle, a system cleanup) rather than as routine update behaviour.

127 tests green. Version 0.25.0 / build 111.

## 0.25.1 — the NOTIFY card went full-height the moment the summary landed (build 112)
2026-10-19

Reported on device: *"worked, but it looked good until summarize finished then it went back to full
height shit"*.

That sentence is the whole diagnosis — **correct until the AI answered** means the fault was in
something that only becomes visible when the summary arrives. That is the risk strip.

The strip was added to the card as a **`MATCH_PARENT`-height child of a `WRAP_CONTENT` card**. In a
`WRAP_CONTENT` parent a `MATCH_PARENT` child forces the parent to match *its* parent, so the card
grows to the whole window. It stayed hidden because the strip is `GONE` until `applyAi`, so the
card measured fine, the fade-in and countdown looked right, and the screen only snapped the instant
the model returned.

**This is the 0.6.2 bug, reintroduced.** The rule from that release — *a decorative fill belongs in
the background or a fixed-size view, never as a `MATCH_PARENT` child of a `WRAP_CONTENT` parent* —
was written down and then not applied when the strip was added in 0.17.0. A rule that lives only in
a changelog entry is not a rule.

Fix: the strip is `WRAP_CONTENT` height in the layout tree, and `applyAi` stretches it to the
card's **measured** height after the text is laid out (`root.post`). Only the strip's own height
changes — the card's size stays decided by its text, which is what keeps it small.

Verified before rebuilding that no other `MATCH_PARENT`-height child remains under the card: every
remaining `MATCH_PARENT` in `NotifyOverlay` is width-only or paired with `WRAP_CONTENT`.

127 tests green. Version 0.25.1 / build 112.

## 0.25.2 — full-screen intent is OPTIONAL, and the checklist stops nagging (build 113)
2026-10-19

**Measured on device:** with FSI revoked and draw-over-apps granted, a SECURE calendar request
still opened the modal and was approved — `APPROVED` in 8797ms, and `APPROVED` can only come from
the modal Activity. So the `ctx.startActivity` path alone is sufficient **while the screen is on**.

That turns the old "Full-screen popup: tap to grant" row into a false alarm: it reported a warning
for a permission the app demonstrably does not need for the common case, and a checklist row that
cries wolf is one the user learns to ignore.

The row is now **optional**, and honest about why it still exists:

- granted → `Lock-screen popup · granted (wakes a locked screen)`, green
- not granted → `Lock-screen popup · optional — tap if approvals miss on a locked screen`, muted,
  **not** a warning colour

**The permission is NOT removed**, and that restraint is the point: the test ran with the screen
on. FSI is what wakes a **locked** screen, so the locked-screen path is still unproven. Removing the
permission on evidence that does not cover its only remaining job would be the same mistake as
trusting it in the first place. Remove it once a locked-screen test passes.

Also corrected here: an earlier entry in this file claimed an update silently revokes FSI. It does
not — `permissions.fullScreenIntent` read `true` straight after installing 0.25.0 over 0.24.x. The
Play Store's auto-revocation applies to Play-installed apps; these are sideloaded.

127 tests green. Version 0.25.2 / build 113.

## 0.26.0 — the summary describes the PAYLOAD, not the route (build 114)
2026-10-19

Mido, after writing 8 todos: *"I didn't like the AI summary. I expected the todolist adding one
to say Adding 8 todos to PhoneBridge or something"* — then, naming why it matters: *"the payload
is the whole reason I added AI, so it's critical to describe it too"*.

He is right, and the cause was upstream of the model. The facts handed to it were:

```
summary: todoist: /secrets/todoist/call
facts:
- TARGET: POST https://api.todoist.com/api/v1/sync
- BODY: {"commands": [{"type": "item_add", ...      <- truncated at 200 chars
```

Two failures stacked:

1. **The deterministic summary described a ROUTE.** `describe()`'s fallback branch produced
   `todoist: /secrets/todoist/call`, which says nothing about what is happening.
2. **The payload was reported as a truncated string.** A 5.3 KB body cut at 200 characters shows
   the model that *something* is being sent and gives it no way to count what.

No amount of prompt engineering fixes that: the model cannot name a number it was never shown.

**Fix — describe the shape, deterministically.** New `core/PayloadShape` (pure, Context-free,
unit-tested) turns a JSON body into `8 commands (item_add ×8)`. Rules: an array of objects is
counted BY `type` (the convention every batch API uses), an array of scalars is a count, a scalar
names the FIELD and never its value, an empty array contributes nothing, and a non-object body
returns null so the caller falls back to an honest byte count.

`describe()` gains a `/secrets/` branch that produces
`todoist · POST /api/v1/sync · 8 commands (item_add ×8)` — true with or without the AI, and
readable in the audit log on its own.

The prompt now says: *describe what the PAYLOAD does, not the endpoint; if the summary names a
count, say it — "Adding 8 todos" beats "updating tasks"*.

**A test that cannot fail, caught and fixed.** The first version of `PayloadShapeTest` inlined a
COPY of the logic and asserted against it — it could never fail. Extracting `PayloadShape` into a
real object made the test real: breaking `items` → `WRONG` fails it (verified), and 133 tests pass
when restored.

133 tests green. Version 0.26.0 / build 114.

## 0.26.1 — the prompt cap is a BUDGET, and the shape is ADDITIVE (build 115)
2026-10-19

Two corrections from Mido, both right.

**1. "the itemadd x 8 thing should be an addition to the context not a replacement."**
In 0.26.0 the payload shape was folded into the `summary:` line, which meant it displaced what
that line would otherwise have said. It is now its **own fact row** in the secrets plugin:

```
- TARGET: POST https://api.todoist.com/api/v1/sync
- PAYLOAD: 8 commands (item_add ×8)      <- the shape, as its own fact
- BODY: {"commands": [...]}              <- and the payload, still there
```

Additive, so the model gets both the shape AND the content. The human gets both rows in the modal
too, which is the more valuable half: the shape is what makes a 5 KB payload judgeable at a glance.

**2. "what's the model limit? and what's the longest cap you recommend."**

The real numbers, from `LiteRtLm.kt`:

```
MAX_CONTEXT_TOKENS = 1024   (KV cache: input + output)
MAX_OUTPUT_TOKENS  =  128
-> input budget    =  896 tok
   system prompt   ~  265 tok  (1,061 chars)
   scaffolding     ~   30 tok
   -> facts        ~  601 tok  ~ 2,400 chars
```

**A flat per-row cap was the actual bug**, not the number 200. It was fine for six short rows and
catastrophic for one 5.3 KB body, because the constraint is on the TOTAL and a per-row number
cannot know how many rows exist. New `core/PromptBudget` divides the budget across however many
rows there are: **600 chars max each, 120 floor, 2,000 total.** Two rows get long excerpts, eight
get short ones, and the total always fits.

Two things fell out of writing it:

- **A clipped value is now marked `…`.** The old cap cut silently, so the model asserted
  confidently about half a payload. Silence is the bug as much as the length.
- **Past ~16 rows the floor multiplied out exceeds the budget**, which would silently overflow the
  context window — a failed request, since the AI is core. The renderer therefore stops adding rows
  when the next would break the budget and prints `(+N more facts not shown)`. An honest omission
  beats a prompt the model cannot read.

139 tests green (6 new for the budget: the total-fits property across 1–20 rows, the floor, the
max, the clip marker, and that an unclipped value is left untouched). Version 0.26.1 / build 115.

## 0.26.2 — the broker's verb comes from the OUTBOUND METHOD (build 116)
2026-10-19

Mido tapped "always notify" on a Todoist read and it silenced deletes too: *"I clicked meaning it
as a read only operation."*

**Not a mis-tap — the pair had no read in it.** The broker is ONE route for every third-party call:

```kotlin
Route("POST", "/secrets/$secretName/call", ..., Verb.EXECUTE, ...)
```

So policy, the audit label, the exception button and the AI facts all keyed on `todoist/EXECUTE`.
"Reads only" was **inexpressible**, and the audit log said `todoist.execute` for a GET.

**The fix: derive the verb from the method in the payload.**

```
GET / HEAD / OPTIONS  -> READ
DELETE                -> DELETE
everything else       -> WRITE
```

New `core/OutboundVerb` (pure, unit-tested). The mapping is by **effect**, and it errs loud: only
methods that provably change nothing are READ, and anything unrecognised is a WRITE — because the
one direction that can hurt is guessing quiet, which would make a destructive call approvable by
silence on a NOTIFY card. A POST-based search API therefore reads as WRITE; visible and
interruptive is the safe way to be wrong.

**Designed for the smallest possible diff**, as asked:

- `Route.verbFor: ((Req) -> Verb)?` plus `Route.effectiveVerb(req)` — opt-in per route, so every
  other route keeps its static verb and the gate is not rewritten.
- Dispatch computes the verb **once** into a local and the six consumers read that local, so they
  cannot disagree about what the request was. That single local IS the fix; the alternative was
  six independent derivations.
- A second `bindRoute` after the body is parsed, because the first bind ran before the body
  existed and could only record the static fallback. `bindRoute` is a plain field-set, so the
  later call wins with no duplicate label.

**Consequence, accepted:** existing `todoist/EXECUTE` rules stop matching. Fine under the
no-standing-backward-compat rule — not live, so the fix is to re-tap.

144 tests green (5 new: the read set, DELETE as its own verb, the mutating set, and that the
fallback is LOUD for `TRACE`/`PROPFIND`/empty — plus case/whitespace tolerance). Version 0.26.2 /
build 116.

## 0.26.3 — the prompt's worked example was being parroted (build 117)
2026-10-19

Mido: *"still says adding 8 todos which is VERY misleading"* — on a **read**.

**I caused this, one release after fixing the last one.** The prompt said:

```kotlin
"say it: 'Adding 8 todos' beats 'updating tasks'"
```

To a 0.6B model a concrete example is not an illustration, it is a **template**. It copied the
sentence onto every request, including reads that add nothing. The previous release taught it the
word "8"; this release taught it the whole phrase.

**Two fixes, because removing the example is not enough on its own.**

1. **No worked example in the prompt.** The rule is stated abstractly — *"Do not state a quantity
   unless the summary or facts contain it"* — because a model that needs an example will copy it.

2. **`core/SummaryGuard` — a deterministic check the model cannot talk its way past.** Two lexical
   rules, both cheap:

   - **The verb must match the language.** A `READ` must not claim to mutate (`add`, `delet`,
     `updat`, …). Read vocabulary is checked first, so "listing tasks, adding nothing" survives —
     an accurate sentence must not be discarded for a word used in negation.
   - **A stated quantity must exist in the facts.** If the description says "8", something the
     model actually saw must contain "8". A believable number is the most dangerous kind of error
     because "8 todos" reads as measured.

   Rejection yields **no AI line**, not a corrected one. The modal still renders every
   deterministic row, so a blank summary section is honest where an invented one is not — the same
   fail-closed choice as `parse` returning null on malformed JSON.

**The lesson worth keeping:** for a small model, an example in a prompt is instruction-shaped. Any
future prompt change should be checked against "will it copy this sentence verbatim?" — and the
guard now catches the answer when it does.

152 tests green (8 new for the guard, including the exact reported case). The guard test was
**proven to fail**: breaking the empty-description check fails `an empty description is not shown`.
Version 0.26.3 / build 117.

## 0.26.4 — the facts named a URL, so the summary could only restate it (build 118)
2026-10-19

Mido: *"says read todoist api both times, absolutely useless"*.

And it was **accurate** — that is the problem. For a GET the model received only:

```
TARGET: GET https://api.todoist.com/api/v1/tasks
SECRET: ***
HOST: api.todoist.com
```

There is nothing else to say about that. **A bigger model would produce the same sentence**, because
it is being asked to describe a URL. So this is a FACTS bug, not an intelligence bug — the third time
in this feature that the model was blamed for what the pipeline fed it.

Two additions, both deterministic (true with or without the AI):

- **The summary names the RESOURCE.** `describeSecretCall` now takes the last meaningful path
  segment — `todoist · GET tasks` instead of `todoist · GET /api/v1/tasks`. Derived from the path
  rather than a per-API dictionary: this route is generic (any secret, any host), so a table would
  be a second thing to maintain and would silently miss every API nobody added.
- **The query is part of the prompt AND the modal.** `QUERY: project_id=…, limit=…` is often the
  only substantive content of a read — a project scope, a filter, a search term. Without it the
  modal says "read todoist api" and the human has nothing to judge.

The summary line carries the query too: `todoist · GET tasks · query: project_id=…, limit=50`.

**The standing lesson, now three releases deep:** when a summary is unhelpful, read the facts before
touching the model. Every time so far the pipeline was the fault — a truncated body, a route string,
a bare URL.

152 tests green. Version 0.26.4 / build 118.

## 0.26.5 — the AI's text now reaches the log (build 119)
2026-10-19

Mido: *"let's add the AI text to logs"*.

Every round of tuning this feature has been blocked on the same missing fact. The log recorded
`summarised in 1198ms: risk=LOW` — the timing and the bucket, never **the sentence**. So "what did
the modal actually say?" was a question only a human holding the phone could answer, and four
releases went by asking it.

Two log points now carry the text, through `Debug.log` so it lands in **both** channels — the
on-screen ring (`GET /debug` → `logTail`) and logcat (`GET /logs`):

- **Accepted:** `ai[todoist/READ] 1198ms risk=LOW why="…" said="…"`
- **Rejected by the guard:** `ai[REJECTED verb=READ] said="Adding 8 todos"`

The rejected line matters most. A guard rejection is the model being wrong, which is precisely the
event worth seeing, and it was previously one `Log.w` line that `/logs` could not reach.

Redaction runs inside `Debug.log`, so a summariser that echoes something token-shaped cannot leak
it through the new line.

Recorded because it generalises: **a diagnostic that reports a duration but not the produced text
answers "did it run", never "was it right".** Both are needed, and only one was being logged.

152 tests green. Version 0.26.5 / build 119.

## 0.27.0 — insets on every screen, and more room above the model row (build 120)
2026-10-19

Mido: *"fix inset. Add more top margin to the model"*.

**Insets — all six remaining screens.** Only `ApprovalActivity` had system-bar handling (added in
0.23.0); `MainActivity`, `SettingsActivity`, `LogActivity`, `LogDetailActivity`, `SecretsActivity`
and `GoogleLoginActivity` had none. The app is edge-to-edge at targetSdk 35, so content drew under
the status bar and pinned controls sat under the gesture bar. This was the third item on the
test-sweep list and had been open since it was reported.

One shared helper — `Theme.inset(root)` — rather than six copies:

- **Insets, never a fixed dp.** Bar height varies per device, OEM and orientation.
- **Adds to the existing padding** instead of replacing it. Several roots already pad for breathing
  room, and clobbering that would have made every screen flush to the edges — the fix would have
  traded one layout bug for another.
- **Top and bottom only.** Horizontal insets are each screen's own concern; adding them here would
  double-pad.
- Returns its argument, so a call site reads `setContentView(Theme.inset(root))`.

`LogDetailActivity` has three `setContentView` sites (aged-out, unparseable, normal) but each
branch returns immediately, so only one ever runs and the listener cannot stack.

**Model row — 16dp above it, on BOTH branches.** The model control can be a large action button
(during download) or a plain status row (when ready). The margin is applied to both, because
applying it to only the button makes the spacing collapse the instant the download finishes — a
layout jump at exactly the moment the user is watching that row.

Also in this release: **8 OpenClaw todos closed**, verified against the code first rather than from
memory (Material 3, the UiKit cleanup, the AI engine, the risk badge, the modal arm delay, both
alert sounds, and the NOTIFY card's AI line). Each closed individually with its own `sync_status: ok`.

152 tests green. Version 0.27.0 / build 120.

## 0.27.1 — the prompt is logged, and content leads the facts (build 121)
2026-10-19

Mido, on a write: *"ai desc says POST https://api.todoist.com /api/v1/tasks this is BS. Are you sure
we are passing all body text to the model?"*

**I was not sure, and I had no way to become sure.** I had been asserting "the body reaches it" by
reading the code, three times across three releases. That is the exact failure this project keeps
paying for, so the first fix is not the prompt — it is the evidence.

**1. The prompt itself is now logged.**

```
ai-prompt[todoist/WRITE] plugin: todoist ⏎ verb: WRITE ⏎ … ⏎ facts: ⏎ - TARGET: … ⏎ - BODY: …
```

Through `Debug.log`, so it reaches both `/debug` → `logTail` and `/logs` → logcat. Truncated at 600
chars, which is enough to see the STRUCTURE — which rows exist and what leads — without flooding the
ring. "What did the model see?" is now answerable from the log instead of from confidence.

**2. Content-first fact ordering, which is a real fix and not a tidy-up.** A 0.6B model reads the
first fact and largely describes THAT. With `TARGET: POST https://api.todoist.com/api/v1/tasks`
first, every write summarised as the URL — the body was present and simply never reached. The
prompt now orders rows by audience: `PAYLOAD`/`BODY`/`QUERY` lead, `TARGET`/`HOST`/`SECRET` trail.

**Two audiences, two orders, one source of facts.** The MODAL keeps URL-first because a human
scanning an approval wants the address. The MODEL gets content-first because it will restate
whatever it reads first, and "POST tasks" says more than a URL ever will.

Recorded because it generalises: **a prompt is read in order, and a small model weights the
beginning.** Fact ordering is a prompt-engineering decision, not a data-model one.

152 tests green. Version 0.27.1 / build 121.

## 0.28.0 — swapped to Qwen3-1.7B (build 122)
2026-10-19

Mido: *"swap"* — after the 0.6B copied `TARGET` verbatim even with the body FIRST in the prompt
(`ai-prompt: - PAYLOAD: content ⏎ - BODY: {"content": "TEMP — prompt-log test"} ⏎ - TARGET: …` →
`said="POST https://api.todoist.com/api/v1/tasks"`).

**The diagnosis, from evidence rather than belief.** Six releases of fact-tuning could not fix it:
truncation, route strings, bare URLs, resource names, query rows, content-first ordering. Each was a
real defect and each helped. The 0.6B still pattern-matched a distinctive string out of the prompt
instead of reading it. That is a model ceiling, not a pipeline bug — and the prompt log from 0.27.1
is what finally made it provable instead of arguable.

**The swap is four constants, by design:**

| | 0.6B | 1.7B |
|---|---|---|
| file | 0.34 GB | 0.98 GB |
| inference | ~1.2 s measured | ~3.4 s est |
| RAM idle | ~700 MiB | ~2.0 GiB |
| gated | no | no |

Same family, same `dynamic_wi4b32_afp32` quantisation recipe — so the chat template that killed
`mixed_int4` is the one already proven working. `MODEL_ASSET` names a different file, so the app
re-downloads automatically and the old 0.6B file is simply unused; reverting is one constant.

Both budgets still fit (10s SECURE, 10s NOTIFY). **The real cost is memory, not latency:** ~2 GiB
resident on a 24/7 foreground service makes the app a prime LMK target. The `onTrimMemory` release
valve already exists for exactly this and a killed engine reloads rather than denying — so the
failure mode is "slower next time", not "bridge down".

**What this is expected to fix, and what it is not.** It should compose a sentence from the facts
instead of echoing one. It will not make a bad fact set good — the deterministic summary already
carries the payload, and that work stands on its own with the AI switched off.

152 tests green. Version 0.28.0 / build 122.

## 0.28.1 — engine load no longer counts as inference time (build 123)
2026-10-19

The 1.7B swap appeared to fail on the first request:

```
04:19:34  engine creation starts (OpenCL init)
04:19:49  engine ready                        <- ~15s
04:19:52  inference starts
04:19:54  "inference 19843ms exceeded budget 10000ms; discarding"
```

**The timer was started before `createEngine()`.** So a cold start — mapping a 1 GB model and
initialising OpenCL — was charged to the *inference* budget, and a perfectly good answer was
discarded as "too slow" purely for being the first one.

That is a measurement bug, and it had been there since the summariser was written. It only became
visible now because the 0.6B warmed fast enough (~0.4 s) that the load cost never mattered.

**Fix:** `val start` moved to AFTER the engine exists, so the budget measures what it claims to
measure — the human's wait for a summary, not the one-off cost of setting up the runtime.

**Why this is not a fudge to make the bigger model pass.** The budget exists to bound how long a
decision waits for a helpful line. Engine construction is setup: `warm()` is supposed to have paid
it at service start, and when it has not, the correct answer is to make the FIRST request slow, not
to silently return no summary. What was happening before was the worst of both: the work completed
and then got thrown away.

**Still true and worth stating:** ~2 GiB resident on a 24/7 foreground service means more cold
reloads after `onTrimMemory`. This release makes each one cost time rather than a missing summary.

152 tests green. Version 0.28.1 / build 123.

## 0.29.0 — APPROVE wears the risk colour (build 125)
2026-10-19

Mido: *"lame description but it increased the risk detection which is alone very useful. make the
approve button color match the risk label color"*.

Worth recording his first clause: **the swap half-worked.** The 1.7B's description is still weak —
it echoed a prompt field until that field was removed in 0.28.2 — but its RISK assessment is
genuinely better: `risk=HIGH why="Writes to external API"` on a write, where the 0.6B said `LOW`.
That is the one output a small model can be trusted with, because it is a single label rather than
composition. The swap is justified on that alone.

**The change:** APPROVE is tinted by the risk bucket — green / amber / red for LOW / MEDIUM / HIGH.

Three design decisions, each to avoid a specific trap:

- **One mapping, in `Theme.riskColor`.** The card had its own private palette (`0xFF81C784` and
  friends) while the modal used the semantic colours, so "HIGH" was literally two different reds
  depending on where you looked. Both now delegate to one function; a second copy is how two
  surfaces start disagreeing about what a word means.
- **DENY stays NEUTRAL.** Two coloured decision buttons read as a pair of equal choices — exactly
  what the 0.23.0 pass separated them to avoid. Red APPROVE beside grey DENY still says
  "approve, carefully".
- **No risk = no colour.** While loading, and when the fetch FAILED, the button keeps its resting
  green rather than implying safety. An absent assessment is not "safe", and painting it green
  would say that it was.

Implemented through an `applyRisk` holder rather than touching `allow` directly: the tick that
paints is created ~150 lines before the button exists, and `applyArmed` already solved that exact
ordering problem the same way.

152 tests green. Version 0.29.0 / build 125.

## 0.30.0 — PAYLOAD quotes values, so the model can name the thing (build 126)
2026-10-20

Mido: *"Says Create task with title and description. This is toooo generic from what i want, I want
something like Creating a test todo item or something. experiment with the prompt"*.

**The prompt was not the problem — the FACT was.** `PAYLOAD` reported field NAMES only:

```
- PAYLOAD: content · description
- BODY: {"content": "TEMP - write test 2026-10-20", "description": "delete after test"}
```

So the model could only restate the field list. It could not say "Creating a test todo item" because
that text existed in `BODY` and nowhere it was reading first. **Same lesson as six releases of this
feature: when a summary is generic, read the facts before touching the model.**

`PayloadShape` now quotes each scalar, clipped to 60 chars:

```
- PAYLOAD: content="TEMP - write test 2026-10-20" · description="delete after test"
```

The full text still lives in `BODY`; this row exists to be *read*, not to carry the payload.

**Credential-shaped keys are named but their values are withheld.** A blunt substring check
(`token`, `secret`, `password`, `auth`, `key`, `credential`, `cookie`, `session`). This string
reaches both the model prompt AND the on-screen AI line, so echoing a token would print it. The
field is still reported, so the model knows something was set — it just does not see what. A false
positive costs one uninformative field; a false negative leaks a credential into a log and a screen.

Tests went 152 → 154: the old "names the field, never its value" assertion was replaced (it encoded
the behaviour being fixed), plus coverage for clipping and the credential guard.

Also this session: **3 temp tasks deleted** (verified by re-read, not by trusting `sync_status`), and
one false positive correctly ignored — "Secrets: **temp**lates + 10 famous integrations prefill" is a
real backlog item that merely contains the word.

154 tests green. Version 0.30.0 / build 126.

## 0.31.0 — the model gets the request body VERBATIM (build 127)
2026-10-20

Mido: *"no fucking processing of any kind. the full json as is + plugin name + url + our system
prompt"*.

He is right, and this closes an eight-release arc of getting it wrong.

**Every previous version transformed the payload first.** Truncated it at 200 chars; reordered its
rows; derived a "shape" from its arrays; quoted its scalar values; rendered it through a budget.
Each step was a judgement about *what mattered*, made by us, before the model ever saw the request.
Each produced a summary that described our view of the request rather than the request.

**Now: `plugin`, `verb`, `route`, `url`, and the body exactly as the caller sent it.** Nothing
derived, nothing reshaped, no rows. `RequestFacts.rawBody` carries the bytes captured by
`readRawBody` before parsing, so it is what arrived — not a re-serialisation.

The only remaining concession is `PromptBudget`, and it is a hard platform limit rather than a
judgement: an input that overflows the context window fails the whole request, because the AI is
core. So the window went **1024 → 8192 tokens** (the old value could not hold a 4.9 KB body), and
the fact budget **2,000 → 16,000 chars**. When it does bite, the truncation is *marked* — a silent
cut presenting half a payload as whole is the exact failure this feature kept paying for.

Also raised `MAX_PER_ROW` 600 → 12,000, which existed to stop one row starving others but was
clipping real bodies to their first few fields.

**The system prompt was rewritten for what it now receives:** "You are given the request body
exactly as the caller sent it. Read it and say what the request actually does, naming the SPECIFIC
thing it acts on… 'Creating a test todo item' beats 'Create task with content and description'."

`SummaryGuard` now checks stated quantities against the raw body rather than a rendered row list —
it validates against what the model actually saw.

155 tests green. Version 0.31.0 / build 127.

## 0.32.0 — the detail is GUARANTEED from the body, not requested (build 128)
2026-10-20

Mido: *"no you shit, even the example you gave is not what i want. I want FUCKING DETAILS FROM THE
BODY"*.

He was right twice. The example I wrote — "Creating a test todo item" — was **my invented phrase**,
not text from his body. And prompt wording had failed eight releases running, so asking a ninth
time was not a plan.

**So the detail is no longer requested. It is supplied.**

New `core/BodyDetail`: given the verb and the raw body, it produces the line itself —
`Writing "test todo item"`, `Deleting "old notes"`, `Reading "today"`. `LiteRtLm.summarize` keeps
the model's sentence only when it actually quotes a body value; otherwise the model's **risk** is
kept (which it is genuinely good at — a single label, not composition) and the description comes
from the body.

**Three real bugs surfaced while testing it, all in code I had just written:**

1. **`optString` on a container returns RAW JSON, not ""** — so the walk quoted `["a@b.com"]` and
   `{"query":"today"}` at the user as if they were prose. Fixed with a `scalar()` gate that accepts
   only strings and numbers.
2. **The fallback quoted routing metadata.** A brokered GET carries `{"url":…, "method":"GET"}`,
   and the fallback happily returned `"GET"` — producing `Reading "GET"`. Fixed with a
   `NOT_CONTENT` deny-list (url, method, headers, ids, version).
3. **`Sending` was wrong phrasing** for a create; `Writing` is true of every write without guessing
   which kind.

All three were caught by `BodyDetailTest`, not by reading the code — which is the point of writing
tests for the part that decides what the human reads.

Also removed the blunt `namesSomethingFromTheBody` rule I had added to `SummaryGuard`: it broke four
existing tests and, worse, **rejection means no line at all** — so it punished the human for the
model's failure. Guaranteeing the detail is strictly better than refusing the summary.

165 tests green (10 new for `BodyDetail`). Version 0.32.0 / build 128.

## 0.32.1 — reverted to Qwen3-0.6B (build 129)
2026-10-20

Mido: *"too slow and I think it crashed. new plan: recert to the old small one."* — and *"revert,
only model"*, so nothing else changed.

**The 1.7B was the wrong trade.** It measured ~3.4s per inference and ~2 GiB resident, and the
bridge process died three times in one morning: twice while idle and once mid-request, during an
inference. Whatever the precise kill — low-memory reaper or a native abort, unconfirmed because the
process took its log with it — the memory was the only difference from the 0.6B, which had run for
days at ~700 MiB.

**What the 1.7B actually bought, in the end: better risk labels.** That is all. Its descriptions
were still structural restatements — *"Create task with content and description"* — which is exactly
why the description is now built deterministically by `BodyDetail` rather than asked of any model.
Once composition stopped riding on the model, the remaining job was a single risk label, and that
is the kind of task a small model handles reliably. So 1.3 GiB of extra resident memory was buying
a marginal label improvement and an unstable service.

Four constants, as designed for a swap:

```
MODEL_ASSET    qwen3_1_7b → qwen3_0_6b
MODEL_URL      Qwen3-1.7B → Qwen3-0.6B
EXPECTED_SIZE  977,184,032 → 344,671,744
EXPECTED_SHA   2a72330b… → 03e7da1e…
```

The filename differs, so the app re-downloads the 345 MB file. **The old 1.7B file stays on disk and
is dead weight** (~1 GB) — nothing deletes it, because deleting a user's file to reclaim space is
not this app's call.

The doc comment in `ModelRegistry` was rewritten: it still argued for the 1.7B, which would now be a
lie sitting next to the code.

165 tests green. Version 0.32.1 / build 129.

## 0.32.2 — a failed start says WHY (build 130)
2026-10-20

Mido: *"on server fail user should see clear error"* — after reporting *"i click start server
nothing happens"*.

He was right on both counts, and they were the same defect seen from two sides. The service
refused to bind (no tailnet address) and called `stopSelf()`, which left `serverUp=false` and the
reason in `lastError`. The hero card then rendered with `!serverUp` checked **before**
`lastError`, so the branch that prints the reason was unreachable in exactly the situation that
sets it. Tapping START looked like a dead button: the service started, refused, stopped, and the
screen kept saying *"Tap START SERVER to begin"*. The refusal was never missing — it was
computed, stored, and then thrown away one line before it reached the screen.

**The fix is structural, not a reordered `when`.** The precedence rule now lives in
`ui/ServerStatus.kt` — a pure object with `state`/`title`/`detail`/`checklistValue` — because an
ordering rule that decides what a human sees cannot be tested from inside an Activity, and this
one had no test at all. The project has paid for this shape twice before (`ResponseBody.classify`,
`HostAllowlist`): a decision inlined in the view or the I/O path is a decision nobody can
exercise, so the wrong branch is the one that ships. `ServerStatusTest` pins the ordering, and
it was proven to fail — sabotaging the `FAILED` branch (`lastError.isNotBlank() && serverUp`, so
it could never fire with the server down) turned 4 of its 8 tests red before being reverted.

What the user sees now:

- `● Could not start` on a **DANGER** surface, with the reason underneath in the danger colour —
  not the muted body colour it used to share with log lines.
- The Setup checklist row says *"not running — see the error above"* instead of "stopped", which
  read like a contradiction under a red heading.
- `startBridge()` clears `lastError` first, so a retry after fixing the cause cannot briefly show
  the old reason while the new start is still binding.

The error strings themselves were also rewritten to be actionable rather than diagnostic. The
tailnet refusal — the one that fired here — now reads *"No Tailscale address on this phone. Open
the Tailscale app, connect it, then tap START SERVER again."* It previously said *"no tailnet
address found; refusing to bind 0.0.0.0"*, which names an internal decision the user cannot
act on. Same treatment for the bind failure, the unreadable policy file, and the unreadable
secret index.

**Not changed: the refusal itself.** Binding to the tailnet address and refusing to fall back to
`0.0.0.0` is a deliberate security decision (S8) and stays. A bridge that silently binds every
interface when Tailscale is down would be reachable from any network the phone joins.

173 tests green (8 new in `ServerStatusTest`). Version 0.32.2 / build 130.

## 0.32.3 — four leaks that were killing the phone (build 131)
2026-10-20

Mido: *"my phone was DYING until I uninstalled the shit out of the app!"* — and he was right,
which makes this the release where the most important bug was not the one being chased. Two
hours went into the Tailscale path because that is what the *symptom* looked like: the bridge
would not bind, so the server never came up. Tailscale was real, but third-order. The app was
the disease.

Four defects, all confirmed by reading the code after he uninstalled (so none of this is
device-measured — see the honesty note at the end):

**1. `NotifStore` had no cap at all.** It was `items[sbn.key] = sbn` on a `LinkedHashMap`, with
nothing ever evicting. Every notification the listener saw stayed resident for the life of the
process, and each value is a whole `StatusBarNotification` — a `Bundle` of extras plus whatever
bitmaps the posting app attached. The process is a `specialUse` foreground service, i.e. one the
OS is *obliged* to try to keep alive, so the growth had no ceiling and no pressure to stop it.
This is the one that made a phone feel like it was dying. Now bounded at
`NotifCapacity.MAX_ITEMS` (200), oldest-first, applied on both `put` and `seed` — `seed` because
a busy shade on first bind is exactly the bulk path that would otherwise bypass the cap.

**2. The model was never released when the service stopped.** `onTrimMemory` released it, but
that is a HINT the OS may never send, so a stopped service could hold ~700 MiB indefinitely.
`onDestroy` now calls `ModelRegistry.release()`. The model FILE is untouched; the next start
reloads it.

**3. The warm-up ran BEFORE the bind check, so every failed start paid for it.**
`onCreate` spawned the warm thread, and `onCreate` runs before `onStartCommand` — so a start
that immediately refused (no tailnet address, unreadable policy, bad port) still loaded the
engine and ran a full throwaway GPU inference, then called `stopSelf()`. He tapped START
repeatedly while the bind was failing, so each tap bought an engine load for a service that
never served a request. The warm moved into `onStartCommand`, after `markRunning(true)`: only a
server that exists pays for it.

**4. `warm()` was check-then-act with no lock.** `engine ?: createEngine()` means two concurrent
callers can each build a full engine — two ~700 MiB mappings for one model. `warm()` and
`release()` now share the same monitor, so a release cannot race a load and leak the engine it
was about to assign.

**Why the capacity rule is a separate pure object.** `NotifStore` holds `StatusBarNotification`,
which does not exist on the JVM — so the unbounded map shipped for dozens of releases while
`testDebugUnitTest` stayed green, because no test *could* see it. `NotifCapacity` is the
Android-free half and `NotifCapacityTest` pins it, asserting on the returned keys rather than
counts: a count-only test passes when the code evicts the newest entry, which would quietly drop
the notification a human just received — a worse bug than the leak being fixed.

**Honest scope note:** these are code-reading findings. He uninstalled the app before any of it
could be measured, so there is no `/debug` memory figure, no `/logs` trace, and no device
confirmation that 0.32.3 behaves better. It ships as an untested-on-device fix and should be
described that way until he confirms it.

181 tests green (8 new in `NotifCapacityTest`). Version 0.32.3 / build 131.

## 0.33.0 — keep the config, never the secrets (build 132)
2026-10-20

Mido: *"I didn't like losing all my config on a fresh install. can we keep everything except the
secrets on uninstall?"*

`android:hasFragileUserData="true"`. Android then shows a **"keep app data?"** checkbox on
uninstall; ticked, `filesDir` and SharedPreferences survive — the gate policy and its rules, the
audit log, and the account email. That is the config that was actually being lost.

**Secrets cannot survive, and this is not an exclusion — it is the platform.** The vault's
wrapping key lives in `AndroidKeyStore`, and a Keystore entry is deleted on uninstall no matter
what this flag says. So a kept-config reinstall restores the wrapped data key and its IV while
the key that unwraps them is gone. `allowBackup` also stays `false`: this keeps data on THIS
phone across a reinstall and sends nothing off the device, so the refresh token still never
leaves.

**That half-state was a trap, and fixing it is the real work in this release.** Two things
combined to make it a permanent dead end:

1. `vaultKey()` **generates a key when the alias is missing**. So the act of trying to unlock
   would mint a fresh VK, then fail to unwrap the old blob with it (GCM tag mismatch) and throw
   `vault_unwrap_failed` — an error that reads like corruption.
2. `isUnrecoverable()` only checked for a **missing wrap IV** (the 0.14.x bug), so this new case
   answered `false` and went down the ordinary unlock path — which can never succeed.

Result without a guard: a prompt that accepted a fingerprint and failed, every single time,
forever, with no reset offered. The same dead end as the 0.14.x vault, reached a new way.

So `VaultState` was added — a pure object over three booleans (`wrappedExists`, `ivExists`,
`keyExists`) resolving `ABSENT` / `UNLOCKABLE` / `UNRECOVERABLE` / `ORPHANED`. `SecretVault.state`
reads the key presence with `containsAlias`, **never** by calling `vaultKey()`, because that
getter would create the key and erase the evidence. `lockOnLoad` routes `ORPHANED` to the reset
dialog with its own wording, because "an old build destroyed your vault" is a lie when the truth
is "you reinstalled and kept the config".

`VaultStateTest` pins all eight combinations and was proven to fail: deleting the `ORPHANED`
branch sent it red exactly there, then back to green.

188 tests green (7 new in `VaultStateTest`). Version 0.33.0 / build 132.

## 0.33.1 — the KV cache was eating the phone (build 133)
2026-10-20

Mido: *"something is definitely wrong all this shit happened after we reverted our large AI model
with the small one, something was fucked up badly it's like the server eats ALL memory"*. He was
right, and it took me two wrong answers to get there.

**The revert was incomplete.** 0.32.1 changed exactly one source file — `ModelRegistry.kt`, four
constants naming the 0.6B artifact. It never touched `LiteRtLm.kt`, so the context window stayed at
the value 0.31.0 had raised it to **while the 1.7B was live**. 0.31.0's own comment said the quiet
part: *"The cost is KV cache: this is the dominant memory term."*

The arithmetic, for Qwen3-0.6B at fp16 (28 layers, 8 KV heads, 128 head dim):

```
KV bytes/token = 2 * 28 * 8 * 128 * 2 = 112 KiB

 1024 tok -> ~112 MiB
 8192 tok -> ~896 MiB   <- what 0.32.1 actually shipped
 2048 tok -> ~224 MiB   <- this release
```

So "revert to the small model" cut the weights by ~650 MB and left the cache **~800 MB larger**
than the 0.6B had ever run with. Net heavier than the 1.7B it was meant to replace.

That explains the whole afternoon, which none of the earlier fixes could: a foreground service
holding ~1.3–1.5 GB applies memory pressure → Android's low-memory killer reaps background
processes → **Tailscale is a background VPN and a prime victim** → the phone loses its `100.x`
address → `tailnetAddress()` returns null → the app correctly refuses to bind → no server. Fixes
aimed at the bind and the error message changed nothing because the bind was never the problem.

**What changed:**

- `MAX_CONTEXT_TOKENS` 8192 → **2048**.
- `PromptBudget.MAX_TOTAL_CHARS` 16,000 → **4,000** and `MAX_PER_ROW` 12,000 → **4,000**. The
  budget's own comment already said it "has to be derived from the CONTEXT WINDOW, not picked by
  feel" — so leaving it at 16,000 against a 2,048-token window would have overflowed the window and
  failed the request outright, since the AI is core.
- The stale doc block in `PromptBudget` that still justified its numbers against a 1024-token
  window is rewritten. A limit outliving the constraint that justified it is exactly how this bug
  happened once already.
- `PromptBudgetTest`'s "a 5 KB payload fits without clipping" was **inverted into two tests**,
  because the trade changed deliberately: a 4.9 KB body is now clipped AND marked, while a 3.5 KB
  body still passes whole. Both halves are asserted, so neither can drift silently.

**`/debug` now reports real memory.** `storage.memoryFreeBytes` was never an answer to "how much is
the app using" — it reports the device's free RAM, which says the phone has less left but never
what took it. Every question about this app's footprint therefore needed a calculation from the
model constants, and the calculation was wrong for weeks because nothing on the device contradicted
it. The new `memory` section reports `totalPssBytes`, `javaHeapBytes`, `nativeHeapBytes`,
`otherPssBytes`, `javaUsedBytes`/`Committed`/`Max`, plus `aiContextTokens` (the window the running
build was compiled with) and `aiModelResident`. Native is split out on purpose: a ~896 MiB KV cache
is native and would be invisible in a heap-only view — precisely the mistake that hid this.

189 tests green. Version 0.33.1 / build 133.

**Still unmeasured.** The ~896 MiB figure is arithmetic from the model configuration, not a reading
from the device: the bridge would not come up long enough to answer `/debug` while this was being
diagnosed. The new `memory` section is what makes the next check a measurement — 0.33.1 should show
`totalPssBytes` dropping by roughly 670 MB against 0.33.0.

## 0.34.0 — Options screen, and "off" that means off (build 134)
2026-10-20

Two preferences, one new screen, and one seam that replaces nine scattered call sites.

**Rename first, so the diff stays readable.** `SettingsActivity` was the EXCEPTIONS screen and had
been misnamed since it was created — the skill docs refer to it as "the rules author" while its
own class comment says where exceptions are authored. It is now `ExceptionsActivity`, and the new
screen is `OptionsActivity`. One file each, no ambiguity about which one a setting belongs to.

**The two toggles, both defaulting OFF** (Mido, 2026-10-20):

- **Fingerprint for approval modals.** `requiresBiometric` becomes `mode == SECURE && policy.fingerprintForModals`.
  The secret vault is deliberately untouched: its key is `setUserAuthenticationRequired(true)`, so
  no setting can remove its fingerprint, and `lockOnLoad` still unlocks it at app start. A locked
  vault answers `secret_locked` exactly as it already does under NOTIFY — which never touched
  biometrics either, and is the precedent that made this a one-line rule rather than a new consent
  model. The first draft of this plan assumed a dilemma ("two taps, or let the modal ignore the
  option") that did not exist; NOTIFY had already answered it.
- **AI on/off.** Off means no model loaded, no download offered, no request described, and no
  `503 model_not_ready` — ever.

**`AiGateway` is the seam.** The root problem was `Route.aiSummary` carrying two meanings at once:
"this route does not want a summary" AND "this route is exempt from the readiness gate". Two
meanings in one flag is why a *global* off switch had nowhere to live — the gate consulted the
route, callers consulted the route, and `ModelRegistry` was reached directly from six places.
Now `AiGateway` owns readiness, warming, releasing, summarising and status. A route keeps one
honest meaning — *does this route want a description* — and every question about whether an AI
exists is answered in one function from one setting. `BridgeService`'s three `requiresAi` sites,
`ApprovalGate`'s and `NotifyOverlay`'s `SummaryProvider.run` calls, and the home screen's model
row all go through it.

**The preferences live in `policy.json`, and that was a deliberate trade with a specific fix.**
Mido: *"single file for now"*. The risk is that policy is fail-hard — an unreadable file refuses to
start the server, which is correct for a security decision and disproportionate for a preference.
So strictness is now split per field rather than per file: `global` and `rules` still throw,
while `fingerprintForModals` and `aiEnabled` fall back to their default and record a warning. And
`VERSION` did **not** move: an existing file parses unchanged, which matters because that file now
survives a reinstall.

`Policy` stays pure, and the first attempt did not. Logging a bad preference from `parse` reached
`android.util.Log`, which throws "not mocked" on the JVM — so the preference test failed with a
`RuntimeException` instead of an assertion. The warning now travels as `preferenceWarnings` data and
`PolicyStore` logs it. That is the same split this project keeps rediscovering: decide in a pure
function, act where Android exists.

The carry-over trap was closed in the same pass: `withRule`, `withoutRule` and the new `withGlobal`
/ `withFingerprintForModals` / `withAiEnabled` all pass every field explicitly, and `applyGate` now
calls `existing.withGlobal(after)` instead of `Policy(after, existing.rules)`. A positional
constructor is one edit away from silently resetting a toggle — which is exactly how the rule table
was wiped once before.

196 tests green (7 new). Version 0.34.0 / build 134.

## 0.35.0 — secret templates, and the rules made testable (build 135)
2026-10-21

Three built-in templates (Mido, 2026-10-20: *"add github, notion only"* — plus `todoist`, which is
the one actually in use). A new **ADD FROM TEMPLATE** button opens a picker; choosing an entry
opens the EXISTING add dialog prefilled with name, docs, key and hosts.

**The value field stays empty and required.** A template cannot create a working secret on its
own, which is the property that makes shipping a fixed list safe — there is no credential in the
source tree, and `SecretTemplatesTest` asserts the data class has no value-shaped field so one
cannot appear later by accident.

**Hosts are prefilled but editable** (Mido chose option 1). The concern was that a template
would make a security decision on the human's behalf: `HostAllowlist` is what stops a stored key
leaving the device somewhere it should not, so a wrong entry widens the blast radius permanently,
at creation. Exact api hosts, never wildcards — `*.todoist.com` would also permit every other
subdomain the vendor runs, which is not what "let this key call the API" means.

**The real work was making the rules testable.** `requireName` and `requireUrlOrEmpty` were
PRIVATE methods on `SecretVault`, so the only way to check a built-in template was to save it on a
device — inside a dialog, after pasting a real API key, where a typo in a list the human was told
to trust surfaces as a `bad_request` toast that reads like their mistake. They are now
`SecretRules`, a pure object, with `SecretVault` delegating to it. One implementation, so "what the
vault accepts" and "what the templates are checked against" cannot drift — the same move already
made for `HostAllowlist`, `ResponseBody` and `VaultState`.

`SecretTemplatesTest` (10 tests) checks every entry against those same objects: name legality,
https-only links, every host passing `HostAllowlist.requireRule`, hosts being exact rather than
wildcards, and a shared-suffix host (`evil-api.todoist.com`) still being refused. **Proven to
fail**: changing Notion's host to `*.notion.com` turned two tests red, then back green.

Two smaller decisions worth recording:

- A template's NAME is read-only in the dialog. The name is the plugin id, and the docs/hosts were
  chosen for that one service — renaming it would attach Notion's hosts to a secret called
  `todoist`.
- The picker refuses to start a template whose name already exists and points at the row's
  `replace` action instead. Opening "Replace" silently could wipe a host list someone had
  deliberately narrowed.

206 tests green (10 new). Version 0.35.0 / build 135.

## 0.36.0 — Drive, and the plumbing that makes bytes possible (build 136)
2026-10-21

Google Drive, with download and upload as the headline. Full `https://www.googleapis.com/auth/drive`
scope, no picker (Mido, 2026-10-21) — so **one re-login is required** after installing this.

**`drive.file` was rejected, and the reason matters.** It is the narrower scope and it cannot SEE a
file the app did not create, so "download my document" would answer not-found for everything already
in the account — defeating the point. The trade is stated plainly rather than buried: the token lives
on the phone, and the approval gate is the only thing between a caller and every file in the account.

**The plumbing refactor.** Every route returned `Resp(status, JSONObject)` and the server wrote
`application/json` — there was no path for bytes at all. `RespPayload` is now a sealed variant
(`Json` | `Stream` with its own content type and length), `Dispatched` carries it, and
`writeResponse` checks the payload before falling back to JSON. The ~13 existing construction sites
are untouched because `Dispatched.body` stayed a `JSONObject`.

**Three leaks were closed BEFORE any byte could flow**, because "streaming is fine as long as it
doesn't leak secrets" is a property, not a promise:

1. **File bytes into the audit log.** `AuditRecord` stores request bodies verbatim, capped at 32 KiB,
   so a streamed upload would have written the first 32 KB of the user's document permanently into an
   append-only log. New `Route.auditRequestBody`, mirroring the existing `auditResponseBody`.
2. **File bytes out of the audit log.** Same cap on responses; `auditResponseBody = false` on the
   download route.
3. **Caller credentials in the log.** `HOP_BY_HOP` filtered four harmless headers and NOT
   `authorization`, so a caller's bearer token was written verbatim into the request log. Now
   filtered. That one was never Drive-specific — it had been true for every route since the audit
   existed.

`auditRequestBody` and `streamsRequest` are deliberately two flags: one is about what the LOG keeps,
the other about MEMORY (`readRawBody` buffers the whole body into a String, fatal for a large file).
An upload needs both, for unrelated reasons.

**Drive routes.** List/search, metadata, create folder, rename, move, trash, download, upload. Trash
rather than delete: `DELETE` is permanent and unrecoverable, `trashed=true` is recoverable for 30 days,
and a destructive action should be the recoverable one unless someone explicitly asks otherwise. The
modal says so. Every mutating route FETCHES the file first so the card names it — an id is not
something a human can approve — and a bad id throws `not_found` before the modal opens.

**The flags live in `DriveRouteFlags`, a pure table `routes()` builds from.** The test cannot
construct a `Context`, so `routes()` is unreachable from the JVM suite — and the tempting fix (copy
the flags into the test) passes no matter what the plugin does, which this project has already paid
for twice. Deriving them means the test reads the same values production uses. `DrivePlugin.route()`
throws when a route has no flags entry, so a new route whose audit behaviour nobody decided fails at
registration instead of inheriting a default that merely looks safe.

212 tests green (6 new in `DriveAuditFlagsTest`). Version 0.36.0 / build 136.

**Not done, deliberately:** the phone-path routes (save-to-Downloads, upload-from-path). Streaming
covers caller↔Drive; those two are for "put this file on my phone" and need scoped-storage handling,
so they are their own pass.

## 0.36.1 — uploads read our own socket to death (build 137)
2026-10-21

First real device test of Drive: **download verified end-to-end, upload failed with
`500 internal_error: Read timed out` in 10.3 s.**

**The bug was `InputStream.copyTo`, and it is entirely mine.** `copyTo` reads until END OF STREAM.
An HTTP request body does not end at end-of-stream — it ends at `Content-Length`, and a keep-alive
client holds the socket open afterwards waiting for the response. So the first upload copied its
20,232 bytes correctly, then blocked on a read that could never return, and died five seconds later
inside `SocketInputStream.read`.

The stack made it unambiguous: `copyTo` → `GoogleApi.putStream` → `DrivePlugin.uploadFile`. Google
was never involved. We were waiting on our own client, which had already sent everything it was
going to send.

**Fix: `BoundedCopy.exactly`.** Copy exactly the declared length, never more, and ask only for what
remains on the final partial read — a full-buffer read with fewer bytes left is the same hang in a
smaller form. A stream that ends early throws `bad_request` naming how much arrived, because a
half-file that reports success is worse than a refusal: nobody goes looking for the missing half.

It is a function rather than an inline loop for the usual reason — the rule is "the body has exactly
this many bytes", which a unit test can prove and an inline copy inside an I/O path cannot.
`BoundedCopyTest` (7 tests) covers the stop-at-length case, the untouched remainder, the truncated
stream, a multi-buffer payload, and the zero-length case.

**What the device test proved before the failure:**

- `GET /drive/files` → 200, real files, correct metadata.
- `GET /drive/files/{id}/content` on a PDF → 200, `Content-Type: application/pdf`,
  `Content-Length: 20232`, and the saved file was `PDF document, version 1.4, 1 page` at exactly
  20232 bytes. **Byte-exact, no truncation** — the streaming download path works.

Also confirmed by that run: the Drive API had to be enabled in the Cloud project first (403
`accessNotConfigured`, not an auth error — a scope failure would have been 401), which is a setup
step rather than a code issue.

219 tests green (7 new). Version 0.36.1 / build 137.

**Still unverified:** the upload path after this fix. The download half is measured; the upload half
is a code-reading fix with a unit test, until a real file lands in Drive.

## 0.37.0 — save a Drive file straight to the phone (build 138)
2026-10-21

`GET /drive/files/{id}/content?to=phone` saves the file to **`Downloads/PhoneBridge/`** and answers
JSON instead of bytes. No `?to=phone` (or any other value) still streams to the caller exactly as
before, so the existing verified download path is untouched.

**One route, two sinks, and merging them was the point** (Mido, 2026-10-21: *"think about merging both
so that i can download a file from drive to phone directly"*). Metadata lookup, MIME resolution, the
Google-native refusal and the failure paths are identical; only where the bytes land differs. Two
routes would have been two copies of that, drifting.

**The permission question had a better answer than the one asked for.** Mido asked how to prompt for a
missing permission on a modal. There is no permission: contributing a NEW file to a MediaStore
collection is always allowed on API 29+, and the restriction applies to modifying files you do not
own, which this never does. So "modern only" costs the user no dialog and no grant — which is why no
prompt flow was built. Worth recording because the alternative (legacy
`getExternalStoragePublicDirectory` + `WRITE_EXTERNAL_STORAGE`) would have needed one, and below API 29
the route now REFUSES with `unsupported_storage` naming the level rather than silently writing to an
app-private directory and calling that "saved".

**Two refusals that happen BEFORE any bytes move**, because a human has already approved by then and
an opaque `google_error` afterwards is the worst place to learn the file was never downloadable:

  - **Google-native docs** (`application/vnd.google-apps.*`) have no binary payload — `alt=media`
    answers `403 cannotDownloadFile`. Refused with a message naming the type and pointing at export.
  - **Folders** are not files, and this release deliberately does not walk trees (Mido: *"ignore
    folders for now"*).

**The filename rule bit immediately, and the test caught it.** Drive names are arbitrary user input;
`"2026/10 report.pdf"` is legal in Drive and an illegal MediaStore `DISPLAY_NAME`. So separators
become `-` and NUL is stripped. But the first implementation also called `.trim()`, which silently ate
the leading space off a real file in the account named `" Cover tmp.pdf"` — legal, and the name the
user would search for. `PhoneSaveTest` failed on exactly that, and the fix was to strip only what is
genuinely illegal. A blank name still falls back to `drive-<id>`, because an unnamed entry in Downloads
is unfindable.

The MediaStore insert is rolled back on a write failure: a half-written row appears in the Files app as
a truncated file with nothing saying it was our fault.

**Still out of scope, deliberately:** folder download (mirror-tree or zip — undecided), upload-from-a-
phone-path, and lazy-load/idle-release for the AI engine.

228 tests green (9 new in `PhoneSaveTest`). Version 0.37.0 / build 138.

## 0.38.0 — the modal names the file properly (build 139)
2026-10-21

Mido: *"the modal needs improvements. i need full file details: full path and name, size(if possible)"*.

**The bug behind the request was bigger than missing fields: the download modal showed NOTHING.**
`GET /drive/files/{id}/content` is a GET, and `rowsFor` returned an empty list for every GET — correct
for "list my messages", where there is no target to name, and wrong here, where the human is deciding
about one specific file. The card was asking someone to approve an opaque request id.

**`rowsFor` now lets an explicit entry win over the read default.** If a plugin DECLARED rows for a
route, they are rendered — GET or not. Only when it did not does the read default apply. A forgotten
entry for a WRITE still throws `internal_error`, which is the guarantee that mattered: an approval
nobody can judge must not exist. The old rule conflated "reads have no target" with "this route is a
read", and Drive is the first plugin where those differ.

**The card now carries path, name, size and type.** A bare name cannot identify a file — two files can
be called `report.pdf` in different folders — so `detailsOf` fetches the metadata and walks the parent
chain to build `My Drive / Projects / 2026 / report.pdf`. Drive has no path field; the path only exists
by resolving each ancestor, which is why the walk is real work rather than a string join. It is bounded
at 12 levels so a malformed chain cannot spin forever inside a modal, and a missing ancestor truncates
the path rather than failing the card — a partial path beats a bare name.

**Size has its own trap, and it is tested.** Drive omits `size` for folders and Google-native docs, so
the caller passes -1. `DriveFormat.humanSize` renders that as **`unknown`**, never `-1 B` or `0 B`:
"unreported" and "empty" lead a human to opposite conclusions and must not look the same. Zero bytes is
a real size and still renders as `0 B`.

One formatter (`fileCard`) serves both the download and trash cards, because a card that shows the size
on trash but not on download is the same bug in a different place.

`DriveFormat.folderOf` was wrong on the first attempt: it trimmed trailing slashes instead of dropping
the filename, so a rename card would have said the file lived in `My Drive / Projects / report.pdf`.
`DriveFormatTest` caught it, and the fix shares one `SEPARATOR` constant with `fullPath` so the two
cannot disagree about what a separator is.

239 tests green (11 new in `DriveFormatTest`). Version 0.38.0 / build 139.

## 0.39.0 — the misclick guard is back (build 140)
2026-10-21

Mido: *"when AI is disabled, we need the 3 seconds protection again to avoid misclicking the dialog"*.

**The regression was a side effect of removing the AI timer.** Before 0.22.0 there was a fixed
`DECISION_DELAY_MS` before the buttons armed. That was replaced by "arm when the summary lands",
which is better in every way — except that with no summary to wait for there is nothing to arm on,
so the gate stamped `enabledAtMs = shownAtMs` and the buttons were live the instant the modal
appeared. A tap already in flight when the card arrives lands on APPROVE.

**`ArmGuard` is one rule used by BOTH arm paths**, not a fix applied only to the branch that broke.
The rule is a MINIMUM time since shown: `armAt = max(readyAt, shownAt + 3000)`. Applying it
per-branch is exactly how these two paths drift apart, and this project has paid for that shape
repeatedly — the audit flag honoured on one dispatch path and ignored on the other is the same bug
with a different field.

The guard can only ever DELAY arming, never bring it forward, so a slow summary is not punished
twice: a request that took 6s to summarise arms at 6s, while one that took 1.2s waits out the
remaining ~1.8s.

**Three things had to change together, or the guard would have been cosmetic:**

1. **The UI predicate.** It tested `enabledAtMs > 0`, which a FUTURE timestamp satisfies — so the
   buttons would have gone live instantly and the guard would have done nothing. Arming is now
   `ArmGuard.isArmed(enabledAt, now)`.
2. **A third countdown phase.** The modal now distinguishes `waiting for the summary` (no arm time),
   `ready in Ns` (guard window), and `auto-deny in Ns` (armed). The guard phase deliberately does
   NOT show the auto-deny clock: the 60s window has not started yet, and showing it would claim time
   the human has not been given. It is labelled rather than silent, because a modal with a dead
   button and no explanation reads as broken, and a human who thinks it is broken taps harder.
3. **`maxWaitMs()`.** The gate's latch budget did not include the guard, so a no-AI request would
   arm at +3s and then get 60s of countdown — 63s of legitimate life against a 60s latch. The
   caller would time out at the exact moment the human still had 3 seconds left, and the UI would
   be showing a window the server had already abandoned. The guard term is now part of the budget.

`ArmGuardTest` (7 tests) pins the boundaries, including the one that matters most — that the guard
only ever pushes arming later. One of my own test assertions was wrong on the first run (arguments
to `isArmed` reversed); the implementation was right and the test was corrected.

246 tests green (7 new). Version 0.39.0 / build 140.

## 0.40.0 — the SSH gate (build 141)
2026-10-21

Mido: *"build a generic ssh gate"* — refined over the session into: raw command string, ed25519
keys generated on the phone, host-key verification, empty allowlist, its own screen.

**It is a mechanism, not a policy.** Mido: *"the bridge offer a mechanism. the gate's role is
showing me exactly what I'm approving, if I approved something stupid that's on me"*. So there is
no command allowlist and no quoting: the string that arrives is the string that runs, and the card
shows it verbatim. The safety property is that card and wire are the SAME string — not that the
string is filtered.

**A premise in the original idea was wrong, and it was the one the safety argument rested on.**
"Send an argv array, no shell, nothing to inject" is not something SSH offers: the `exec` request
carries one string and the server hands it to `$SHELL -c`. There is no argv transport in the
protocol. Rather than fake it with our own quoting — which Mido rejected in favour of a raw string
— the design leans on the card being literal and on the human being the judge. That is stated
plainly here because the argv framing is what made the earlier draft sound safer than it was.

**Keys are generated on the phone and never displayed.** `SshKeygen` produces an ed25519 keypair;
the private half goes straight into the vault and the public half is shown once for
`authorized_keys`. JSch 2.28.8 carries a pure-Java `KeyPairEd25519`, so no BouncyCastle dependency —
verified by inspecting the jar, and the build confirmed D8 is happy with it.

**The private key would have been one tap from the screen, and that is why `SecretMeta.kind`
exists.** `SecretsActivity` offers reveal and replacement for every vault entry; for an SSH key
both would render or accept the private half. Entries are now marked `SECRET` or `SSH`, and SSH
entries show only delete plus a line saying why. One vault, one unlock, and the claim stays true.

**Host keys are confirmed, never assumed** (Mido: *"I confirm first"*). The first connection to a
host REFUSES with `ssh_host_key_unknown` and reports the fingerprint and algorithm; the caller
repeats the call with `trustHostKey` naming it, which pins it. A later mismatch refuses with
`ssh_host_key_changed` — the message says both readings (rebuild or impersonation) rather than
assuming either. Implemented as a `HostKeyRepository`, so JSch aborts the handshake itself; the
earlier draft tried to read `session.hostKey`, which does not exist in this version.

**`/ssh/{name}`** takes `{"command": ..., "trustHostKey"?: ..., "timeoutMs"?: ...}`. Output is
capped at 64 KiB with a 30s default timeout, and the session is always torn down. The command is
stored in the audit log verbatim (Mido: *"store"*) — a remote-exec feature with no record of what
ran would be worse than a log that might contain a secret.

254 tests green (8 new in `SshConnectionTest`). Version 0.40.0 / build 141.

**Not verified on device:** that ed25519 generation actually works on the S22, and that a real
connection authenticates. Both are runtime facts. The first thing to try is creating a connection
and reading back its public key — that proves the generator without needing a server.

**Known gap, stated rather than hidden:** there is no UI to import an existing private key, so
"generate, or use one if already generated" is currently generate-only.

## 0.40.2 — the upload streams, and SSH keys generate (build 143)
2026-10-24

Two independent first-run failures, both reported from the device, both proven on the JVM before
any fix was written.

**A 385 MB Drive upload died at ~150 MB with a connection reset, and the reset was a lie.** The
body was never being streamed: `HttpURLConnection` with `doOutput = true` buffers the ENTIRE body
into a `ByteArrayOutputStream` and only opens the socket afterwards — setting a `Content-Length`
*request property* does not change that. Against this app's 256 MB `javaMaxBytes`, a 385 MB file is
an `OutOfMemoryError` on the request thread, and a JVM dying mid-request looks exactly like a
network reset from the far end. Nothing in the phone's log mentioned memory, which is why the first
diagnosis (the network) was wrong.

Measured at the real size and the real heap ceiling:

```
property only:                        OOM java heap space, 128 MB sent, heapUsed 195 MB
  at java.io.ByteArrayOutputStream.write
  at sun.net.www.http.PosterOutputStream.write
setFixedLengthStreamingMode(385 MB):  385 MB sent, heapUsed flat at 3 MB
```

`HttpStream.fixedLength` now owns that call, and it is made BEFORE `getOutputStream()`. It also
emits `Content-Length`, so the explicit header is gone — two sources for one number invite a
mismatch. `HttpStreamTest` proves the rule structurally rather than by timing: with streaming the
server receives body bytes WHILE the client's output stream is still open; with the old code it
receives exactly zero until the close.

**`generate key` threw `UnsupportedOperationException` on the very first SSH connection.**
`SshKeygen` called `KeyPair.writePrivateKey`, which is built on `KeyPairEdDSA.getPrivateKey` — and
that class does not implement it. Every key this app creates is ed25519, so the failure was total:

```
Exception in thread "main" java.lang.UnsupportedOperationException
  at com.jcraft.jsch.KeyPairEdDSA.getPrivateKey(KeyPairEdDSA.java:75)
  at com.jcraft.jsch.KeyPair.writePrivateKey(KeyPair.java:163)
```

The fix is `writeOpenSSHv1PrivateKey(out, null)`, which encodes from `prv_array` directly and works
for every key type; the null passphrase is the documented no-passphrase case. `SshKeygenTest` now
covers generation, the PEM frame, the ed25519 round-trip, and that `JSch.addIdentity` accepts the
result — the last one mattering because a key that generates but cannot be loaded would fail every
run as `ssh_bad_key`.

**Both of these were unit-testable and untested.** JSch is plain JVM code and `putStream` uses a
JDK class, so neither needed the phone. The lesson recorded rather than implied: the one call that
could not possibly work was the only one with no test.

263 tests green (5 new in `SshKeygenTest`, 4 in `HttpStreamTest`). Version 0.40.2 / build 143.

**Still not verified on device:** that the 0.40.2 APK uploads the 385 MB file, and that a real SSH
connection authenticates. The streaming change is proven at the HTTP layer, not through Drive.

## 0.40.3 — trust-on-first-use actually works (build 144)
2026-10-24

The SSH gate had a THIRD defect, behind the two in 0.40.2, and this one made the feature's central
safety story unreachable.

**A refused host key was classified by its message text, and the message did not match.**
`SshExec` searched `e.message` for `"UnknownHostKey"` / `"not included"` to decide whether a
failure was `ssh_host_key_unknown`. JSch 2.28.8 says neither — an untrusted host throws
`JSchUnknownHostKeyException` with the message `reject HostKey: <host>`. So the first connection to
ANY new host fell into the generic `ssh_failed` branch, which reported no fingerprint and no
`trustHostKey` instruction. The confirmation step — the entire point of "host keys are confirmed,
never assumed" — could never be reached.

Measured against a real sshd on this host:

```
no pin   -> com.jcraft.jsch.JSchUnknownHostKeyException   "reject HostKey: 127.0.0.1"
bad pin  -> com.jcraft.jsch.JSchChangedHostKeyException   "HostKey has been changed: 127.0.0.1"
```

The fix classifies by **exception type**. `JSchHostKeyException` is caught first, and the two
subclasses map to `ssh_host_key_unknown` and `ssh_host_key_changed`. Anything else that is a
host-key refusal becomes `ssh_host_key_refused` — a new code, deliberately NOT a generic failure,
because it is still a decision a human must make and the fingerprint must stay visible. That code
had to be added to `ErrorCodes.MAP` (409, not 500); the existing guard test caught the omission
before the build did.

**Also fixed:** the plain `JSchException` branch was rethrowing the raw exception, so an auth
rejection escaped as an unmapped 500. It is now a coded `ssh_failed`.

**Verified end to end against the real sshd on this box**, driving the app's own compiled classes
(not a reimplementation) with a key generated by the fixed `SshKeygen`:

```
STEP 1  first contact, no pin   -> ssh_host_key_unknown + SHA256:c6PYkM0Z...
STEP 2  trust it, run a command -> exitCode=7, stdout "openclaw|exit-test", 104ms
STEP 3  reconnect, pin stored   -> exitCode=0, stdout "pinned-run-ok"
STEP 4  wrong pin               -> ssh_host_key_changed, nothing ran
```

The reported fingerprint was cross-checked with `ssh-keygen -lf` on the host key and matched
byte for byte. The temporary test key was removed from `authorized_keys` afterwards.

271 tests green (8 new in `SshHostKeyTest`, including one that runs against a real sshd when one is
present and skips otherwise). Version 0.40.3 / build 144.

**Not verified on device:** that the APK's vault unlock, key generation and network path behave the
same as the JVM harness. The logic is proven; the phone is not.

## 0.40.4 — ed25519 is implemented here, because JSch's cannot run on Android (build 145)
2026-10-24

**The real reason `generate key` never worked, after three releases of "fixes".**

`com.github.mwiede:jsch` is a **Multi-Release JAR**. Its Java-8 base classes are stubs that throw,
and the real implementations live under `META-INF/versions/15/`, which the Android runtime does not
read:

| config key | base class (what Android loads) | real class (unreachable on Android) |
|---|---|---|
| `keypairgen.eddsa` | `jce.KeyPairGenEdDSA` — every method throws "requires Java15+" | `META-INF/versions/15/...` |
| `ssh-ed25519` | `jce.SignatureEd25519` — the **constructor** throws | same |

The fallbacks are `com.jcraft.jsch.bc.*`, which need BouncyCastle — not on this classpath.
Reproduced by deleting `META-INF/versions` from the jar (exactly what Android sees):

```
FAILED: JSchException: java.lang.NoClassDefFoundError:
  org/bouncycastle/crypto/params/Ed25519PrivateKeyParameters
```

Every earlier JVM verification loaded the versioned classes and proved nothing about the phone. That
is the mistake this release is mostly about.

**The fix: ed25519 implemented directly from RFC 8032** (`Ed25519.kt`), with `MessageDigest` as the
only dependency, registered into JSch's two hooks by `SshKeygen`'s `init` block. No provider, no
BouncyCastle, no API-33 requirement (this app's `minSdk` is 26 and platform Ed25519 arrived in 33).

Correctness is pinned by RFC 8032's published vectors, for **both** halves:

- key derivation: TEST 1/2/3 public keys match exactly
- signatures: TEST 1 (empty message) and TEST 2 (one byte) match byte for byte

**Two bugs the vectors caught while writing this:** `verify` compared `ByteArray`s with `==`
(reference equality — it rejected every valid signature), and the verification equation was written
as `[S]B − [k]A` with a twisted-Edwards negation in the wrong coordinate. Signing was right the
whole time; only verification was wrong, which is exactly the class of bug a shape-only test misses.

**Also fixed:** `addDialog` caught `Exception`, but the failure was a `NoClassDefFoundError` — an
`Error`. So the dialog threw, nothing was caught, and **nothing was logged**. That is why the
symptom was "a key never appears" with no error anywhere. It now catches `Throwable` and logs to
`/debug`'s ring buffer.

**Proven on the Android-shaped jar** (app classes, `META-INF/versions` removed):

```
PEM_HEAD=-----BEGIN OPENSSH PRIVATE KEY-----   PUB=ssh-ed25519 AAAA...IFJljvkE crab
DERIVED_MATCH=true   TYPE=ssh-ed25519
ssh-keygen -y -f <generated>  -> the same public key
ssh -i <generated> openclaw@127.0.0.1 'echo OPENSSH_OK'  -> OPENSSH_OK
```

**Not proven:** authentication through JSch itself. Generation and signing are individually
correct (RFC vectors, and the adapter's signature verifies against its own public key), but a full
JSch session against the local sshd still answers `Auth fail for methods 'publickey,password'`
while the OpenSSH client accepts the same key. So JSch is producing a signature the server rejects
even though our `sign()` matches the RFC — unresolved, and NOT claimed as working.

281 tests green. Version 0.40.4 / build 145.

## 0.40.5 — the hooks register on the CONNECTION path too (build 146)
2026-10-24

The last blocker, found by asking the right question: *what loads when the app restarts and a
command is run, without anyone opening the SSH screen?*

**Registration lived in the wrong place.** `SshKeygen`'s `init` block installed JSch's two ed25519
hooks. But `SshKeygen` is only loaded by the SSH screen — a run that connects with an already-stored
key never touches it. So after a restart, `SshExec` connected with JSch's DEFAULT hooks, which are
the BouncyCastle classes that do not exist on the device:

```
hook BEFORE SshExec loads: com.jcraft.jsch.bc.SignatureEd25519
```

JSch swallows the resulting `NoClassDefFoundError` inside its signer and reports only
`Auth fail for methods 'publickey,password'` — indistinguishable from a rejected key, which is why
this looked like a crypto problem for hours. Generation worked (the screen had loaded), and signing
worked (the tests generated a key in the same JVM first), so every isolated test passed while the
real path stayed broken.

`JschHooks.install()` is now idempotent and called by BOTH `SshKeygen` and `SshExec`, so whichever
loads first is enough.

**Verified in restart order** — `SshExec` touched first, `SshKeygen` never loaded before the run:

```
hook before anything: com.jcraft.jsch.bc.SignatureEd25519
hook after SshExec:   com.mido.phonebridge.plugins.ssh.JschEd25519Signature
STEP1 ssh_host_key_unknown
STEP2 exit=9 stdout=[openclaw|SSH_GATE_WORKS]
STEP3 exit=0   (pinned reconnect)
```

All of it on the Android-shaped jar (app classes, `META-INF/versions` removed). The earlier
`Auth fail` in the same harness was a test-setup artifact: `authorized_keys` had been restored
between generating the key and connecting, so the key genuinely was not authorized.

**Also fixed in this release:** `addDialog` caught `Exception` around a call that failed with
`NoClassDefFoundError` — an `Error`. The dialog threw, nothing was caught, and nothing was logged;
the symptom was a key that simply never appeared. It now catches `Throwable` and writes to the
`/debug` ring buffer.

281 tests green. Version 0.40.5 / build 146.

**Still not verified on the device**: that the APK's vault, biometric unlock and network path behave
like the harness. Everything below the UI is now proven on the Android-shaped jar.

## 0.40.6 — a JSON null is not the string "null" (build 147)
2026-10-24

First real device call after 0.40.5, and it failed in a way that contained its own contradiction:
the error code said the host key **changed**, while the message printed `pinned: null`.

Both cannot be true. `ssh_host_key_changed` is only produced when a pin exists. So the pin was not
null — it was the four-character STRING `"null"`, which renders identically.

**`optString` on a JSON null differs between implementations.**

```
toJson writes JSONObject.NULL

Android org.json  -> optString returns "null"   <- the phone
reference org.json -> optString returns ""       <- what the unit tests run
```

`fromJson` did `optString("hostKeyFingerprint").takeUnless { it.isBlank() }`. On the phone that
yielded the string `"null"`, which can never equal a real fingerprint, so **every connection to a
never-pinned host reported `ssh_host_key_changed` forever** — and trust-on-first-use could never
complete, because the first call has to be `ssh_host_key_unknown` for the caller to learn the
fingerprint at all.

This is the second bug of the session with the identical root shape: **the unit tests run a
different implementation than the device.** The first was the Multi-Release JAR; this is `org.json`.

The fix reads the field with `has` + `isNull` (identical on both) and treats a literal `"null"` as
absent. A real fingerprint is asserted to survive the round trip, because a guard eager enough to
eat a legitimate pin would silently disable host-key verification — worse than the bug.

**Verified against the real target** (the NUC, `100.64.0.12`):

```
ssh-keyscan -> SHA256:NqIEY3V6FtVJXDRJmCn76lciI5r/IUg6pnOuAQg3PAk
app reported -> SHA256:NqIEY3V6FtVJXDRJmCn76lciI5r/IUg6pnOuAQg3PAk
```

So the connection reached the host and read its key correctly; only the classification was wrong.

285 tests green (4 new in `SshConnectionTest`). Version 0.40.6 / build 147.

**Still not verified:** a full approved round trip that runs a command on the NUC. The gate returned
`408 approval_timeout` on the first attempts (nobody at the phone), and the tap that did land hit
the classification bug above before any command could run.

## 0.40.7 — tests run Android's runtime now (build 148)
2026-10-24

Mido, after the third bug of the day with the same root: *"can you stop this fucking different env
shit then?"* — and that was the correct call. The bug class was never the individual defects, it was
that **`./gradlew testDebugUnitTest` did not run the code the phone runs.**

Two shipped failures came from exactly this:

| divergence | test JVM | Android |
|---|---|---|
| `optString` on a JSON null | `""` (reference `org.json`) | `"null"` |
| JSch ed25519 classes | reads `META-INF/versions/15/` | ignores it, loads the stub |

**Unit tests now run under Robolectric** (4.14.1), against real Android framework classes, and
`org.json:json` is REMOVED from the test classpath — keeping it would let the reference
implementation win and re-open the hole.

That change immediately did its job: **the moment it was enabled, dozens of previously-green tests
failed** with `Method opt in org.json.JSONObject not mocked`. They had been passing against a
different implementation the whole time. Every test class now carries
`@RunWith(RobolectricTestRunner::class)`.

`AndroidJsonProofTest` makes the runtime itself an assertion — it checks that `optString` returns
the Android string `"null"`, and that `Build.VERSION.SDK_INT` exists. If the suite ever drifts back
to a bare JVM, it fails with a clear message instead of the difference surfacing as a phantom bug
on the phone.

288 tests green, all on Robolectric. Version 0.40.7 / build 148.

**Unchanged and still true:** the SSH feature's own status. Host-key reading works (the phone
reported the NUC's real fingerprint), generation and signing are proven, and the JSON-null fix from
0.40.6 is what unblocks first contact. What has NOT happened is a full approved round trip that
executes a command on the NUC.

## 0.40.8 — the NOTIFY card gets the same 3s guard (build 149)
2026-10-24

Mido: *"add the same 3 seconds waiting period before ANY touch event on the notification works so
that I don't approve or cancel by mistake"*.

**The overlay had no guard at all.** `ArmGuard` existed and was wired into the SECURE modal, but the
NOTIFY card's touch listener approved on `ACTION_UP` unconditionally:

```kotlin
} else if (ev.actionMasked == MotionEvent.ACTION_UP) {
    settle(entry, Reason.APPROVED)     // no time check
}
```

So a card arriving under a finger already in motion — exactly the case the guard was written for —
approved a request nobody had read. The modal was protected; the surface that appears WITHOUT
deliberate navigation was not.

Both decisions are now gated through `ArmGuard.decisionsAllowed(shownAt, now)`:

- the tap that approves
- the `x` that dismisses (it answers REJECTED, so it is equally a decision)

**Dragging is deliberately still allowed while unarmed.** Moving the card is not an answer to the
request, and blocking it would read as a frozen UI rather than a careful one.

**`shownAt == 0` is refused.** `Entry.shownAt` is 0 until the view is attached, and a real clock
reading is far past `0 + 3000`, so treating it as a timestamp would leave an unattached card fully
armed. There is an explicit guard and a test for it.

A refused tap is not silent: it logs `tap ignored: still in the 3s guard` to `/debug`, so a card
that seems unresponsive can be told apart from one that is broken.

291 tests green (3 new in `ArmGuardTest`, including one asserting the overlay and modal guards are
the SAME duration so the two surfaces cannot drift). Version 0.40.8 / build 149.

## 0.41.0 — auto-start after reboot, and a status label that stops lying (build 150)
2026-10-25

Two outages the same morning, both from the app never re-checking anything it claimed.

**1. The bridge stayed down after a reboot.** The manifest had **no `<receiver>` at all** and no
`RECEIVE_BOOT_COMPLETED` permission, and `MainActivity.startBridge()` was the only
`startForegroundService` call site in the codebase. `START_STICKY` keeps the service alive through
a system process kill; it cannot survive a reboot, because after one nothing ever asks the service
to run. The phone rebooted at ~05:40 (tailscaled logged `peer disco key changed`, which is an
ephemeral key per tailscaled process) and the bridge stayed down until it was noticed hours later.

- New `BootReceiver` on `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`, gated by a new
  `autoStartOnBoot` preference (**default ON**, parsed with the existing `lenientFlag` so a garbled
  value falls back rather than refusing to start).
- `MY_PACKAGE_REPLACED` is half the value on its own: sideloading a build kills the process, so
  without it every update left the bridge down until someone opened the app.
- `exported="true"` is required and is not a hole — both actions are protected broadcasts that
  only the system can send.
- **`specialUse` is permitted from a boot receiver on Android 15.** Only `dataSync`, `camera`,
  `mediaPlayback`, `phoneCall`, `mediaProjection` and `microphone` are banned, so the service's
  existing type is fine. Checked against the platform docs rather than assumed.
- **A boot start races Tailscale**, and the old code turned that race into permanent death: no
  `100.x` address yet → `stopSelf()` → `START_NOT_STICKY`. That path is now a bounded retry ladder
  (10s / 30s / 90s / 180s) in `core/StartupRetry.kt`, kept pure and unit-tested so the schedule can
  be asserted without an Android runtime — the same extraction rule as `HostAllowlist` and
  `VaultState`. Exhausting it posts a notification instead of dying silently.
- The notification is its own channel (`bridge_status`, `IMPORTANCE_DEFAULT`) and its own id
  (4243). Reusing the running notification's `IMPORTANCE_LOW` channel would have made the reminder
  silent, which is the same as not having one. Posted **before** `stopSelf()` — after that there is
  nothing left to post it from.
- The toggle gates app-load auto-start too: **one toggle, one meaning**, "the server starts
  itself". A setting whose OFF state still started the server on every app open would not mean off.

**2. The app painted a green "Running" while every caller got `Connection refused`.** `serverUp`
was an **assertion**: set `true` once after `start()` and `false` only in `onDestroy`, never
re-verified. NanoHTTPD ships `isAlive()` (socket open + accept thread alive) and the app called it
**zero times**. `ServerStatus.state()` consumed the flag as truth, and `render()` ran only on
`onResume` plus two one-shot 600ms callbacks, so the label could be arbitrarily stale. `/debug`
reported `up` from the same flag, so a remote caller was misled too.

- New watchdog probes every 60s with `isAlive()` **plus a real TCP connect** — `isAlive()` alone
  only proves the objects exist, not that a caller would get through. On failure it rebinds, since a
  stale tailnet address is the likeliest cause; it notifies only when a rebind also fails.
- `ServerStatus` gains **`UNRESPONSIVE`**, checked BEFORE the healthy/attention pair, so "up but not
  answering" can no longer render as "Running". Its title is `● Not answering`, deliberately not
  containing the word the state exists to retract.
- `render()` ticks every 5s while resumed (cancelled in `onPause`). It reads the CACHED probe
  result — a fresh connect there would be network I/O on the main thread.
- `/debug` gains `alive`, `boundTo` and `lastProbeAtMs`, so "process up, not serving" and "process
  dead" are distinguishable from outside.

302 tests green (was 288): 5 in `StartupRetryTest`, 4 new in `ServerStatusTest` (including the
2026-10-25 bug stated as an input), and preference coverage for the new field in `PolicyTest`.
Version 0.41.0 / build 150.

**Not device-verified yet.** The reboot path needs an actual reboot, and the unresponsive path
needs a server that dies while the screen is on. Both are unproven until then.

---

## 0.41.1 — the bridge now waits for Tailscale instead of dying (build 151)
2026-10-25

**Found by the 0.41.0 device test itself.** Mido turned Tailscale off, got the "not running"
notification as designed, turned it back on — and the bridge stayed down until he opened the app.
The retry ladder had done its job and then called `stopSelf()`, which destroyed the only thing that
could have noticed the tailnet returning. Boot and install were covered; reconnect was not.

- **The ladder no longer stops the service on exhaustion.** It enters a waiting state instead:
  the service stays alive, the notification switches to "PhoneBridge is waiting for Tailscale",
  and it starts serving the moment a `100.x` address exists. The cost is a foreground service
  that was already running; the cost of the old behaviour was a bridge needing a human to poke it.
- **Two wake paths, deliberately.** `registerDefaultNetworkCallback` is the fast one — a VPN
  connecting becomes the default network, which is exactly the event to react to. A 5s interface
  poll backs it up, because `tailnetAddress()` reads interfaces directly and not every interface
  change produces a `Network` object. The poll is one enumeration per tick, no network I/O.
- **`BridgeStatusNotice` is now a pure, tested object.** The copy and the ongoing/autoCancel pair
  used to be inline literals inside `BridgeService`, so a new state could be added while the
  notification still described the old one — no error, no log. Three kinds now, each with a
  distinct title, and `WAITING_FOR_TAILNET` is the one that must tell the human that doing
  NOTHING is correct.
- **The watchdog hands over rather than dead-ending.** A rebind with no tailnet address used to
  post a "not running" notice and return; it now enters the same waiting state, since Tailscale
  coming back is the only thing that resolves it.
- `ACCESS_NETWORK_STATE` added (normal permission, granted at install).

308 tests green (was 302): 6 in `BridgeStatusNoticeTest`, including that ongoing and autoCancel
are always opposites and that the waiting copy says it recovers by itself.
Version 0.41.1 / build 151.

**Not device-verified yet.** The reconnect path needs Tailscale toggled off and back on.

---

## 0.42.0 — NOTIFY runs while the phone is locked, if the vault is open (build 152)
2026-10-25

Mido, 2026-10-25: *"for notification level calls it shouldn't matter if the screen was off or the
phone locked or not, as long as the app was unlocked once for storage unlock"* — and, asked whether
it should be reads-only: *"regardless of verb"*.

**This reverses the original attendance rule, and the reversal is the feature.** Before it, an
unattended request was denied in EVERY mode: `isAttended()` ran pre-gate for all requests, so
`debug/READ` answered `403 denied: gate unavailable: screen off or device locked` while the phone
sat in a pocket. Reads could not run overnight at all.

**The consequence, stated plainly rather than buried:** `NOTIFY` approves on SILENCE. A dark screen
guarantees silence, so this is unattended approval — not "the card was ignored" but "the card was
never legible". The consent model is now *unlocked once, trusted after*, not *someone saw this*.
That is the accepted trade.

- **The bound is the vault session.** `SecretVault.isUnlocked()` is `dataKey != null` — a live
  session, not a stored flag. It dies with the process (restart, reboot, system kill) and on
  `lock()`. So the trust window is exactly "since you last unlocked this app, while this process
  is alive". A cold-booted phone is back to denied, which is what keeps this defensible.
- **`unattendedAllowed(mode, vaultUnlocked)` is pure and tested.** `NOTIFY` → the vault decides.
  `SECURE` → always false, whatever the vault says. A vault session is NOT a substitute for a
  present human, and if that ever returned true the fingerprint gate would be silently off.
- **Unattended requests do not draw a card.** Drawing one would report "shown" for something that
  was never readable. The NOTIFY branch approves, holds the window, and records
  `detail = "unattended"` so the log never reads like an observed decision.
- **The overlay's expiry re-check had to change too**, or the rule would depend on WHEN the screen
  went off: a request that started attended and lost the screen mid-window would still have
  settled `UNATTENDED`. Both the tick and the backstop now accept an open vault.
- **Verb-agnostic by explicit instruction.** The blast radius is bounded by POLICY instead: only a
  route someone marked `NOTIFY` behaves this way, and `NOTIFY` is a deliberate, audited per
  `plugin × verb` choice.

314 tests green (was 308): 6 in `AttendanceTest`, covering the bound (locked vault still denies),
that `SECURE` can never proceed unattended, and that the rule takes no Verb parameter.
Version 0.42.0 / build 152.

**Not device-verified yet.** Needs a locked phone with the vault session open.

---

## 0.42.1 — restore the audit and logcat packages that git never had (build 153)
2026-10-25

**The tree did not compile, and no commit was responsible.** `app/.../audit/` (13 files) and
`app/.../plugins/logs/` (1 file) were missing, so seven sources failed on unresolved
references — `BridgeService`, `MainActivity`, `LogActivity`, `LogDetailActivity`,
`ExceptionsActivity`, `OptionsActivity`, `DebugPlugin`. Nothing in either repository's history
ever contained them.

**Why: the ignore rules were aimed at OpenClaw's runtime data and landed on the source.**
`~/.openclaw/.gitignore` carried bare `audit/` and `logs/` (they exist to exclude the agent's
own audit and log directories). A bare directory pattern matches at EVERY depth, so
`git check-ignore` resolved
`app/src/main/java/com/mido/phonebridge/audit/AuditLog.kt` → `.gitignore:28:audit/` and
`.../plugins/logs/LogcatPlugin.kt` → `.gitignore:16:logs/`. Those files were therefore never
staged, in the in-tree copy or in the new repo, and deleting the in-tree copy during the move
took them with it. The compiled output kept working, which is why nothing noticed until a
clean build.

**Recovered from the Gradle build cache, not rewritten from memory.** The last good build's
`compileDebugKotlin` output still held all 31 classes, so the reconstruction was checked
against real bytecode rather than recollection: every member present in the recovered classes
is present in the restored sources, and the only difference is one private nested `Companion`
that is now inlined. **314 tests green, unchanged** — the same suite, over the same contracts.

- **The doc comments are the loss, and they were rewritten deliberately.** Bytecode carries no
  comments, and in this project the comments are where the reasoning lives — why absence is
  distinct from emptiness, why the id is a counter rather than a random suffix, why a 4xx after
  an approval is signal. Each was restated from the behaviour the code actually has, so a future
  reader still inherits the reason and not just the mechanism.
- **`AuditRecord`/`AuditHeader`/`Decision`/`Truncation`/`AuditDay` are `data class`es again.**
  The bytecode shows generated `copy`/`componentN`/`equals`, and `ExceptionsActivity.save` takes
  an `AuditRecord` as a parameter type — a plain class would compile but change identity
  semantics for no reason.
- **`AuditLog.from` reads the version from `BuildConfig`** rather than the literals the
  decompiler showed (`"0.42.0"`, `152`), which were that build's constants baked in. The
  original source used `BuildConfig`, and a hardcoded pair would go stale on the next bump.
- **`PolicyAudit` likewise** takes the version from `BuildConfig` for the same reason.

**The durable fix is a `.gitignore` that cannot do this again.** The repository's own file uses
`build/`, `.gradle/`, `keystore/`, `apk-out/` — patterns that cannot match a source directory —
and the restored packages are now tracked, so `git status` shows them and a clean clone builds.
The `~/.openclaw` rules are unchanged: they are correct for that repo, and the mistake was
carrying a project inside it, which 07e94200 already ended.

Version 0.42.1 / build 153. Behaviour is unchanged from 0.42.0 — this release restores source,
it does not alter it.

---

## 0.43.0 — a sudo password typed at the modal, never sent over HTTP (build 154)
2026-10-26

**The password does not exist on the wire.** It is typed on the phone, held only in the
Activity and the gate, and written to `sudo -S`'s stdin. A caller cannot supply it, because
there is no request field for it — which is the whole design. A credential the API accepted
would be a credential in transit, and that is the thing this feature exists to avoid.

**Why this shape rather than `NOPASSWD`.** The alternative — a dedicated `bridge` user with a
scoped `NOPASSWD` line — gives the same root with a smaller blast radius, and I argued for it.
Mido chose this one. Both were verified safe; this is the shape that was picked, so this is what
ships, and the reasoning below is what makes it safe rather than merely chosen.

**Detection is a parser, not `contains("sudo")`, because a false positive is a LEAK.** The
password goes to the command's stdin. If the predicate said "yes" for a command that is not
sudo, that password would be handed to whatever it really is — and `cat` would print it into
the HTTP response. So [SudoCommand] tokenizes with quotes and operators honoured, and only the
HEAD of each segment can match: `echo sudo hi` is not sudo, `echo 'x; sudo id'` is not two
commands, `/var/lib/sudo` is not sudo. A false NEGATIVE is harmless by comparison — sudo
refuses for lack of a terminal — and the asymmetry is why the tests are lopsided on purpose.

- **`sudo` must carry `-S`, and that is checked PRE-GATE.** Verified on this host:
  `printf 'x\n' | sudo -k true` answers *"A terminal is required to authenticate"*, while the
  same with `-S` reaches the password check. A sudo command without it can never succeed, so
  refusing it as `sudo_needs_stdin` (400, noise) costs the human no tap. We do not add the flag
  ourselves — that would rewrite a command the card promised to show verbatim.
- **The floor is enforced in the dispatcher, not in `resolve()`.** A password request is forced
  to `SECURE`. `NOTIFY` approves on five seconds of silence from an overlay card with no
  Activity and no biometric: there is nowhere to type, so a NOTIFY rule must not be able to
  reach such a request. `resolve()` sees only plugin + verb and cannot express this, which is
  why the override sits where the route is known.
- **`CharArray`, never `String`, end to end.** `String` cannot be wiped. The Activity reads the
  chars out of the `Editable` directly, the gate copies them, `SshExec` encodes through a
  `CharsetEncoder` over a `CharBuffer`, and every copy is zeroed — including in a `finally`, so
  a throwing handler cannot leave one alive. The audit records THAT a password was supplied (one
  clause in the summary), never the value: nothing to redact, no schema change.
- **The field cannot be harvested.** `FLAG_SECURE` (recents thumbnails, screen recorders),
  `TYPE_TEXT_VARIATION_PASSWORD` with `IME_FLAG_NO_PERSONALIZED_LEARNING` (the keyboard's
  dictionary), `setSaveEnabled(false)` (rotation), autofill excluded, and the copy/cut selection
  menu disabled. APPROVE stays disabled while the field is empty, so an empty password is never
  submitted and rejected three seconds later by sudo.
- **The card states the elevation twice.** A `ELEVATION` row in the target block (a `sudo`
  mid-command is easy to miss at a glance) and one line under the field saying the password goes
  to stdin on this device, not over the network. Without that sentence the field reads as a
  credential harvest, which is exactly what it must not look like.

**335 tests green (was 314): 21 in `SudoCommandTest`.** Most of them pin negatives — that a
quoted `sudo`, a path containing `sudo`, and a mid-command `sudo` are NOT treated as elevation —
because those are the cases where a wrong answer would have handed the password to the wrong
process.

**Known limit, stated rather than hidden:** `bash -c 'sudo rm -rf /'` is not detected, because
`bash` is the segment head. It fails safe (sudo refuses without a terminal) and it cannot leak
the password, since nothing here would have supplied one.

**Not device-verified.** Needs a real phone, a real sudo prompt, and a command whose output would
visibly differ between root and non-root.

Version 0.43.0 / build 154.

---

## 0.43.1 — the misclick guard is 1s, and the card dims while it waits (build 155)
2026-10-26

**One second instead of three**, on Mido's instruction. The guard was never really about a number
of seconds: it exists so a tap already travelling when a card appears cannot approve something
nobody has read. A deliberate tap is ~100ms, so 1s still catches the case; the extra two seconds
were charged to every human for a risk the guard already bounded.

**The reason 1s is safe is the DIM, not the clock.** A disabled control that LOOKS enabled is what
turns a short guard into a misclick. The NOTIFY card previously refused taps in silence — it sat at
full opacity and simply ignored them, which reads as a broken card rather than a careful one. It now
renders at 45% opacity until the guard expires and snaps to full, and that snap is itself the
signal: the card became live and looks like it.

- **Driven from the same predicate the touch handler uses** (`ArmGuard.decisionsAllowed`), not a
  second timer. The picture and the behaviour cannot disagree about when the card went live — the
  same one-source-of-truth rule this project has paid for repeatedly.
- **Multiplied into the fade, not replacing it.** The fade is about appearing; the dim is about
  being live. A card still fading in is also still unarmed, so the two compose cleanly.
- **The modal already had this.** `ApprovalActivity` dims APPROVE to 0.4 alpha while unarmed and
  shows "ready in Xs", so only the overlay was missing the cue. The two surfaces now agree, which
  is what `ArmGuardTest` asserts.

**A consequence worth recording rather than discovering later: lowering the guard does not make an
AI-on modal answerable in 1s.** The measured summary latency is ~1.2s, so the FETCH is now the
binding constraint in the ordinary case — it used to be hidden underneath the 3s guard. That is not
a regression (the guard was never meant to mask the model), but it means the visible wait with AI
enabled is unchanged. A dedicated test now pins this so a future "why is it still ~1.2s" lands on
the explanation instead of a bisect.

`ArmGuardTest` went red on the old 3s assumption exactly as it should, and the stale "3s guard" log
lines now say "misclick guard" — a log that names a duration it no longer has is a log that lies.

336 tests green (was 335).

Version 0.43.1 / build 155.

---

## 0.43.2 — a touch can no longer act while the card still looks dimmed (build 156)
2026-10-26

**Reported on device within a minute of 0.43.1 shipping:** *"wait, I can still dismiss in cool
down"*. The dim and the guard had drifted apart by one frame.

**The bug, exactly.** The tick repaints the card every 33ms, but it only runs on the main thread's
next frame. The touch handler checked `ArmGuard.decisionsAllowed` against the REAL clock. So a touch
landing in the gap between the guard expiring and the next paint was **allowed while the card still
displayed the unarmed alpha** — live control behind a locked-looking surface.

**The log proved it, which is why this was fixable rather than arguable:**
`notify[5013] settle reason=TAPPED shownMs=1023 alpha=0.45`. Tapped 23ms *past* a 1s guard, on a
card rendering at 45% opacity. Behaviour had unlocked; the picture had not caught up.

- **One function now answers "what should this look like"**, `ArmGuard.cardAlpha`, exactly as
  `decisionsAllowed` answers "may this be tapped". The tick and every touch path call the same one,
  so they cannot disagree.
- **Every touch path recomputes the alpha BEFORE it decides.** The gap is closed in the only
  direction that matters: the picture is brought up to date before the decision it gates is taken.
  Both the tap-to-approve and the ✕ dismiss, since both answer the request.
- **Only ever raises alpha.** The guard cannot un-expire, so no touch should ever make a card dimmer.

**Worth stating plainly: the 0.43.1 tests passed while this bug was live.** They pinned the pure
predicate, and the predicate was right — the defect was in the WIRING between predicate and paint.
A unit test on a pure function cannot see a frame boundary. The new test pins the invariant instead:
*at any instant a decision is permitted, the alpha the card would render is full*. That is the
contract the bug violated, and it fails if someone removes the undim.

339 tests green (was 336).

Version 0.43.2 / build 156.

---

## 0.43.3 — STMP rebrand: paper skin, stamp animation, smaller controls (build 157)
2026-10-26

**The app is being rebranded to STMP.** Warm paper/ink palette, Handlee handwriting font, a
rubber-stamp launcher icon, a full-screen stamp animation on approve/deny, and the homepage's
four loose nav buttons collapsed into a Tools row. This build additionally shrinks button text so
longer labels stop overflowing.

- **Palette**: cold near-black/blue toned to warm cream paper + ink, one stamp-red accent.
- **Typography**: Handlee (SIL OFL 1.1) wired into `Theme.text/sectionHeader/button`.
- **Icon**: rubber-stamp vector (absolute `M/L/C` path only — the first arc/quadratic attempt
  failed inflate and made the install report "corrupt" with no icon).
- **Stamp animation**: fires on both APPROVED and DENIED, with a physical thud + haptic.
- **Homepage**: `Tools` row replaces four full-width nav buttons; audit banner moved beside log.
- **Font size**: buttons reduced (small 12f, full 14f) so longer labels fit.

Version 0.43.3 / build 157.

---

## 0.43.4 — stamp voice in the approval copy (build 158)
2026-10-26

**Copy pass, subtle:** the approval header now reads "awaiting your stamp" instead of the flat
"APPROVE?", and the gate/hero sub-lines carry the same stamp voice ("needs your stamp", "waits for
your stamp"). Wording only — no layout, no behaviour.

Version 0.43.4 / build 158.

---

## 0.43.5 — hand-drawn ink underline on section headers (build 159)
2026-10-26

**Drawn-accent pass, first piece.** Section headers now carry a hand-drawn ink underline (a
two-tone translucent `GradientDrawable` stroke under the label) so group labels read as ink on
paper rather than a printed hairline. Pure vector, no raster asset.

Version 0.43.5 / build 159.

---

## 0.43.6 — torn-note card corners (build 160)
2026-10-26

**Drawn-accent pass, second piece.** The hero and gate cards now use `Theme.tornCard`, which
layers a hand-torn paper edge (`R.drawable.torn_edge`, a tintable vector with absolute M/L path
commands — no arcs, same constraint that fixed the launcher icon) over the standard surface, so
cards read as notes torn out of a pad rather than rounded rectangles.

Version 0.43.6 / build 160.

---

## 0.43.7 — the section header is a rule again, not a red bar (build 161)
2026-10-26

**Two defects caught on device, both from 0.43.5/0.43.6.**

**The red bar.** 0.43.5 set a `GradientDrawable` as the section header's *background*, so it
filled the whole TextView — every section label ("SETUP", "ACCOUNT", ...) sat on a solid salmon
block. A background drawable has no concept of "just the bottom edge", so the shape has to be
painted. `Theme.InkUnderline` is a small `Drawable` that draws a ~60%-width rule at its own
bottom, plus a fainter offset second pass for an uneven ink edge.

**The torn card was a no-op.** 0.43.6 layered a 24dp `torn_edge` vector into a `LayerDrawable`
under a card surface — but a tile stretched to card size, drawn in paper colour on paper, is
invisible. It changed nothing on screen. Removed rather than shipped as decoration, along with
`R.drawable.torn_edge`; a torn edge needs to be part of the card shape itself, not an overlay.

Version 0.43.7 / build 161.

---

## 0.43.8 — torn cards, drawn as the card's own outline (build 162)
2026-10-26

**The torn edge is back, built the way it has to work.** 0.43.6 tried to layer a 24dp
`torn_edge` vector over a card surface: a tile stretched to card size, in paper colour on paper,
is invisible, so it changed nothing and was removed in 0.43.7.

A torn edge is a property of the SHAPE, not an overlay. `Theme.TornPaper` is a `Drawable` that
builds a rounded path whose bottom edge steps through a fixed jagged pattern, so the hero and
gate cards are torn paper themselves. The pattern is a constant array rather than `Random`: a
random tear would re-roll on every repaint and the card would visibly crawl. The path is cached
per bounds, so repaints cost one draw call.

Version 0.43.8 / build 162.

---

## 0.43.9 — the stamp mark is set in Handlee (build 163)
2026-10-26

The APPROVED / DENIED word inside the stamp overlay was drawn in
`Typeface.create("sans-serif-condensed", BOLD)` — a system face, so the app's single most
prominent moment was the one place the rebrand did not reach. It now uses `Theme.handFont`, the
same Handlee the rest of the app is set in.

Version 0.43.9 / build 163.

---

## 0.43.10 — the icon's letters are letters, not blocks (build 164)
2026-10-26

The launcher icon's "STMP" was built from filled rectangles after the arc-syntax rewrite, and the
`S` path in particular was malformed — a block of pixels that only read as a letter if you already
knew the word.

The glyphs are now STROKED paths (`strokeLineCap`/`Join` round): a stroke follows the drawn
centreline, so S, T, M and P each read as a letter at 48dp on a home screen. Path data stays
absolute M/L/C only, which is the constraint that keeps the vector inflating on every device.

Version 0.43.10 / build 164.

---

## 0.43.11 — rebrand reaches every screen (build 165)
2026-10-26

The rebrand had stopped at the home screen. Two surfaces still carried the old identity:

**The NOTIFY overlay was still dark.** Its private palette (`BG`, `TEXT`, `MUTED`, the three verb
badge colours) was the pre-rebrand near-black set, so the one card that appears while you are in
another app was the only screen that did not look like the app. Repalletted to the same paper/ink
tokens; the badge fills are now the light paper-tinted variants with dark ink text, which is the
correct polarity on a light card.

**Every remaining `DEFAULT_BOLD` heading is now Handlee** — the home hero and gate titles, and the
title rows on EXCEPTIONS, OPTIONS, the request log and its detail screen. A system bold face next
to Handlee body copy reads as two apps stitched together, and the headings are exactly where the
eye lands first.

Compile-verified only. Not device-verified.

Version 0.43.11 / build 165.

---

## 0.43.12 — the last visible "PhoneBridge" is gone (build 166)
2026-10-26

The notification-listener service still advertised itself to Android as "PhoneBridge
Notifications", so the name a user sees in Settings › Notification access was the one place the
old brand survived. Now "STMP Notifications".

The remaining `PhoneBridge` occurrences are the application id, the package name and the theme
style names — identifiers, not user-visible strings. Renaming those is the parked pre-launch task,
not a copy change: a new application id is a different app to Android, which resets every runtime
permission grant and the notification-listener binding.

Version 0.43.12 / build 166.

---

## 0.43.13 — SSH: keep the tail, bound stderr, return partial output, and detach jobs (build 167)
2026-10-26

Four fixes to the SSH route, all found by using it for real work rather than by reading it.

**A timeout no longer discards what the command said.** The read loop filled a buffer and then
threw `ssh_timeout` at the deadline, which dropped every byte captured so far. A timeout was
therefore indistinguishable from a command that printed nothing — the caller could not tell "slow"
from "silent", and an answer that had already been produced was thrown away with the error. Now
the result comes back with `timedOut: true` and whatever output arrived, and `exitCode: -1`
(JSch's own "no status") rather than a fake `0`. Proven against a real run that printed its answer
and then hung: the text is now visible instead of being replaced by an error.

**Truncation keeps the tail, not the head.** At the 64 KiB cap the old code answered with the
FIRST 64 KiB — for a build log or a test run, exactly the part that does not contain the error.
`SshTail` is a ring buffer that retains the LAST 64 KiB and reports `truncated: true`.

**The reader always drains, so much output cannot become a timeout.** The old loop stopped reading
once the cap was hit, leaving the remote command blocked on a full pipe until the deadline — so
"too much output" surfaced as a timeout. A ring has nothing to lose by continuing to read.

**stderr is bounded.** stdout was capped at 64 KiB while stderr was a plain `ByteArrayOutputStream`
with no limit at all. A command that keeps warning (`curl` retrying, a failing `ssh`) could grow it
until the OS killed the app. Both streams now use the same capped tail, and `truncated` reflects
either one. This asymmetry was an oversight, not a decision.

**Detached jobs: `POST /ssh/{name}/detach` and `GET /ssh/job/{id}`.** A synchronous call is capped
at 300s and torn down at the deadline, so a build, a clone or an agent run either fits or dies with
no exit code; the workaround was composing `nohup … & echo $!` by hand and polling with `tail` in
separate calls. The route starts the command and returns a job id immediately, and the poll route
returns the retained output so far. Bounded on every axis: at most 4 running jobs
(`ssh_job_limit`), a capped tail per job, and finished jobs expire after 30 minutes
(`ssh_job_not_found`). The table is in memory and does not survive a process restart — a job dies
with the app, which is the honest contract rather than pretending a restart kept a socket.

**A detached command may not use sudo, and the route refuses it pre-gate.** The dispatcher wipes
the sudo password the instant the handler returns, and the job runs after that — so it would either
prompt into a closed stdin or keep a live password in a thread nobody is watching. Refused before
the modal, so it costs no tap.

Version 0.43.13 / build 167.

---

## 0.43.14 — paper you can feel: grain, a real thud, the stamp on NOTIFY, and a 3D slam (build 168)
2026-10-27

The rebrand shipped a warm palette, Handlee and torn edges, and it still read as "beige UI" rather
than paper. The stamp and its sound existed but were unreachable in the default gate mode, and the
"3D" never existed at all. Four changes, each fixing one of those.

**Paper grain.** Real paper is never one flat value: it has fibre, sparse darker specks and slight
density variation, and the eye reads that variation as material. A single `setColor` cannot produce
it. `PaperGrain` builds one 96x96 tile from a fixed seed and repeats it through a `BitmapShader`,
so the texture is identical on every repaint — a `Random` inside `draw()` would re-roll each frame
and the surface would visibly crawl, the same trap the torn edge already documents. One tile costs
~37 KB however large the surface is. It is layered ON TOP of the existing drawable rather than
wrapping it, and clipped to the card's radius: an unclipped shader paints square corners over a
rounded card and the corners go visibly dirty.

**A real thud.** The old sound was a 90ms `ToneGenerator` beep — audible, but it read as a
notification chirp rather than something hitting a desk. `res/raw/stamp.ogg` is now a synthesised
knock (low sine burst with a falling pitch plus a short noise transient, ~220ms), played through a
`SoundPool` decoded once and reused. `SoundPool`, not `MediaPlayer`: a `MediaPlayer` allocates a
codec per instance with hundreds of ms of startup latency, which cannot land on the same frame as
the stamp. Before the sample decodes (first stamp after a cold start) it falls back to the platform
tone rather than going silent.

**The stamp now exists in NOTIFY.** It used to fire only on the SECURE modal, so anyone on the
default `AUTOMATIC` mode — where a small card is drawn instead — never saw the moment at all. The
same overlay now fires on `APPROVED` and `TAPPED`, in its own `FLAG_NOT_TOUCHABLE` window: the card
is `WRAP_CONTENT`, so a `MATCH_PARENT` child would force it to full window height (the 0.6.1 bug),
and the stamp has to survive the card's 200ms fade-out to read as one gesture. It never fires on
`EXPIRED`, `UNATTENDED` or `UNAVAILABLE` — those are the absence of a decision, not one.

**The 3D slam.** `rotationX` from -55 degrees to 0 with `cameraDistance` set once, so the stamp
comes DOWN out of the screen rather than merely scaling up. The tilt lands slightly before the
scale settles, so the face is flat when the ink hits — a stamp still angled at contact reads as a
hovering sticker. Set on the View, not the canvas: `Canvas` has no X-axis rotation, and faking it
with a matrix would bypass the camera that makes the perspective correct.

Version 0.43.14 / build 168.

---

## 0.43.15 — the grain reaches the homepage (build 169)
2026-10-27

0.43.14 added paper grain and the homepage still looked identical. Three reasons, all mine.

**The grain was on the wrong drawable.** It went into `surface()`, but the homepage's SETUP cards
use `tornCard()` — a different drawable entirely. So the one screen the user was looking at got no
texture at all. The grain now paints inside `TornPaper`, clipped to the same torn path, so the
texture follows the tear instead of stopping at a rectangle.

**The root was flat colour.** `setBackgroundColor(Theme.BG)` is the largest surface on the screen,
and a flat fill there dominates the whole view. It is now `PaperSheet(BG)`: the base colour plus the
same grain, full-bleed.

**The texture was too timid to see.** Alpha 22 over cream, at phone brightness, is invisible — the
comment claimed "felt rather than seen", which in practice meant not seen. Raised to 34 and the
speck density from 18% to 26%, which is the range where it reads as material without looking like
dirt on the glass.

The lesson worth keeping: a visual change is verified on the screen that was complained about, not on
the helper that looked like the right one.

Version 0.43.15 / build 169.

---

## 0.43.16 — one way to paint a background, enforced by a test (build 170)
2026-10-27

Grain reached the approval gate and the screen root and nothing else, because it lived in two
helpers that some screens use and some do not. A rule that depends on which helper a screen happened
to call is not a rule.

**`Theme.sheet(color, radiusPx)` is now the only way to paint a background.** It returns the base
fill with grain layered over it, and `Theme.sheetDp(ctx, color, radiusDp)` is the dp convenience.
`surface()` and `outlined()` now route through the same two layers, so the three constructors cannot
drift apart.

**Every raw background in the activities is gone.** Eight `setBackgroundColor(Theme.BG)` calls —
Approval, Secrets, SSH, Log, LogDetail, Options, Exceptions, GoogleLogin — now assign
`Theme.sheet(Theme.BG)`. The NOTIFY card, its verb badge and its risk strip were the remaining flat
surfaces, and the card now carries a third `GrainOverlay` layer.

**`GrainCoverageTest` fails the build on a bypass.** It scans `src/main/java` for
`setBackgroundColor(`, `GradientDrawable()` and `ColorDrawable(` outside the `ui` package, and
asserts every hit is either absent or explicitly allowed with a stated reason. The allowlist is
three entries, each saying why it cannot carry grain: a 3dp tint strip, the countdown drain that
draws *under* the card's grain, and the card's own base fill which the risk tint recolours at
runtime.

Two details in that test worth keeping:

- **It guards the guard.** An empty scan fails, because a scanner that finds nothing has broken, not
  a codebase that is clean.
- **Allowlist entries match on file plus a code prefix, never a line number.** The first version
  keyed on `file:line:code`, and every entry stopped matching as soon as anything above it shifted
  — the allowlist failed open, which is the worst way for one to fail. It caught this itself on the
  first run by reporting three "unexplained" sites that were in fact its own allowlist entries.

Version 0.43.16 / build 170.

---

## 0.43.17 — the real stamp sample, first 2 seconds (build 171)
2026-10-27

The synthesised knock was a guess at what a stamp sounds like. Mido supplied an actual recorded
sample (freesound community, 45.5s of several hits), so the app now uses that instead.

**First 2 seconds, not one hit.** The sample is a set of stamps with room tone between them. The
first 2s are taken verbatim with a 150ms fade at the tail so it does not click when the stream
ends; the rest is left unused rather than trimmed to a single knock, because the sequence is what
Mido asked for and second-guessing it would be substituting my taste for his.

Re-encoded to Ogg Vorbis (~20 KB) so the APK does not grow by a megabyte for one effect. Playback
is unchanged: `SoundPool`, decoded once, fired on the same frame as the overlay.

Version 0.43.17 / build 171.

---

## 0.43.18 — every card, button and rule drawn by hand (build 172)
2026-10-27

Straight edges were the last thing making the app look like software instead of paper. Every surface
now has a wobbling outline.

**Why not `DiscretePathEffect`.** It is the obvious answer and it is wrong twice: it chops a path
into segments with a RANDOM deviation, so the border re-rolls on every draw and visibly crawls — the
same trap `TornPaper` already documents — and the result is a shaky straight line rather than a
drawn one. A wobble is a shape, so `SquiggleBorder` builds it as a shape: each straight edge is
subdivided and each segment is a cubic Bézier whose control points alternate above and below the
ideal line. That gives the S-curve of a pen dragged by hand, one bump up and one down, instead of
noise. The deviation comes from a fixed array, so a given width always draws the same wobble.

**Corners stay true quarter-arcs.** A wobbly corner on a 12dp radius reads as damage, not as
handwriting. Only the straight runs wobble.

**Buttons keep their ripple.** The drawn edge goes in `foreground`, not `background`. Replacing the
background would drop `backgroundTintList`, the ripple and the disabled state — the exact regression
`Theme.kt`'s header exists to prevent.

**Separators and section rules are drawn too.** `InkUnderline` is gone; `SquiggleRule` replaces it
and is also what a plain rule uses. Its amplitude is clamped to a third of its own height, because a
1dp view with a 1.5dp wave would draw outside its bounds and get clipped into a straight line by
accident — the failure would look like the feature never shipped.

**Amplitude is 1.5dp.** Below ~1dp it is invisible at phone density; above ~3dp a 40dp button starts
looking like a doodle rather than a control.

**`SquiggleCoverageTest` enforces it.** It scans the whole source tree for `MaterialButton(` and
`setStroke(` and allowlists the sanctioned `Theme` sites by file plus code prefix. The first version
scanned only OUTSIDE `ui/`, which was wrong: `Theme` is the only place either call appears, so the
scan found nothing and its own empty-result guard fired — the test caught its own blindness.

Version 0.43.18 / build 172.

---

## 0.43.19 — the 3D stamp is actually visible (build 173)
2026-10-27

The 3D slam shipped in 0.43.14 and could not be seen. Three separate bugs, all mine, each enough on
its own to hide the effect.

**The scrim tilted with the stamp.** `rotationX` was set on the View, and the overlay is
`MATCH_PARENT` — so the full-screen dim rotated too and the whole thing read as the screen leaning
rather than an object pressing down. The transform now goes through an `android.graphics.Camera`
applied to the CANVAS around the stamp's own centre, so the scrim stays flat and only the stamp has
perspective.

**The tilt finished before the stamp was opaque.** Fade-in was 80ms while the tilt ran 180ms, and
the stamp starts at 2.2x scale — so by the time any ink was visible the face was already flat, and
the entire 3D happened during the transparent phase. Fade is now 60ms and the tilt 340ms: the stamp
is visible for most of the descent.

**The animation started before layout.** `radius` comes from `width`/`height`, which are 0 until the
view is measured, so the opening frames drew nothing. It now starts from `post`, after layout.

`camera.setLocation(0, 0, -12dp)`: a negative Z pulls the camera toward the viewer, which is what
produces foreshortening. At 0 there is no perspective and `rotateX` degenerates into a vertical
squash.

Version 0.43.19 / build 173.

---

## 0.43.20 — the stamp gets to finish (build 174)
2026-10-27

The stamp was being destroyed on its first frame. Two independent teardown races, one per surface.

**The modal killed its own Activity.** `ApprovalActivity` runs a 120ms tick that calls `finish()`
the moment `ApprovalGate.isCurrent(id)` goes false — and `finishWith` resolves the request *before*
it shows the stamp. So the window was already being torn down while the overlay was starting. A
`stamping` flag now suppresses that finish: the stamp IS the exit, and the overlay calls `finish()`
itself from `onFinished`.

**The NOTIFY card faded out underneath it.** The card's fade-out is 200ms while the stamp runs
~1.1s (slam + bleed + settle + fade), so most of the animation played over empty space. For
`APPROVED`/`TAPPED` the settle now returns before scheduling the fade and the backstop; the teardown
is driven by the overlay's `onFinished` instead. If the overlay cannot start at all — no
draw-over-apps permission — it tears the card down immediately, so a missing permission can never
strand a card.

Both paths keep a real exit: the backstop still runs for every reason that has no stamp
(`EXPIRED`, `UNATTENDED`, `UNAVAILABLE`, `TAPPED`-as-dismiss), and `dismissAll` is untouched.

Version 0.43.20 / build 174.

---

## 0.43.21 — no scrim on the card, and a masked sudo field (build 175)
2026-10-27

Two reports from the same test pass.

**The card dimmed the whole screen.** `StampOverlay` always painted a scrim, which is right on the
modal path — the Activity is leaving and the dim reads as the exit. On the NOTIFY path the card
STAYS on screen for the whole ~1.1s animation, so a full-screen dim was just the screen going dark
for a second with nothing to explain it (Mido, 2026-10-27: *"clicking the hint results a full screen
dim for a few seconds"*). The scrim is now a constructor parameter and the card path passes 0.

**The sudo field rendered in clear.** It already set
`TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD`, which is supposed to mask, and it did not on
device. `PasswordTransformationMethod` is now set explicitly: it is the same call `inputType` makes
internally, made where nothing can reinterpret it. The field also keeps its existing protections —
no personalised learning, not saved to instance state, no copy/cut menu.

Version 0.43.21 / build 175.

---

## 0.43.22 — the sudo password actually reaches the gate, and the field really masks (build 176)
2026-10-27

Two bugs that both looked like "the password field is broken", and they were unrelated.

**The gate never learned the request needed a password.** `ApprovalGate.await` takes its own
`needsPassword` parameter, separate from the `ApprovalView.needsPassword` that decides what the modal
DRAWS. The dispatcher passed the view field and never passed the gate one, so
`Pending.needsPassword` kept its default `false`. The result: the field rendered, the human typed
into it, and `resolveWithPassword` refused the password every time — logging *"request 1001 does not
need a password, or expired"* and letting the call time out. Mido, 2026-10-27: *"it didn't timeout I
entered the password and accepted"* — exactly right, and the log proved the tap landed. The gate now
receives `needsPassword` too.

**The mask was being cleared after it was set.** `setInputType` internally re-derives the
transformation from the type, so the explicit `PasswordTransformationMethod` assigned BEFORE it was
silently wiped — which is why the text stayed readable. The transformation is now the last statement
in the field's `apply` block, where nothing above can undo it.

**Password managers no longer offer to save it.** `importantForAutofill` was set to
`IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`, which reads like "no autofill" and means the
opposite: it permits autofill on this view and only excludes descendants. It is now
`IMPORTANT_FOR_AUTOFILL_NO`.

Version 0.43.22 / build 176.

---

## 0.43.23 — the password approve stamps too (build 177)
2026-10-27

The sudo modal was the ONE decision that skipped the stamp. `finishWithPassword` called `finish()`
directly instead of going through `showStamp`, so approving with a password dismissed the modal
instantly with no ink mark and no thud — while every other approve and deny animated.

Mido, 2026-10-27: *"no fucking stamp sound and immediately dismissed"* — accurate on both counts,
and it had been true since the password path was written.

It now resolves, wipes the password, and then calls `showStamp(approved = true)` like the other
paths. A resolve that FAILS no longer finishes the Activity either: nothing was approved, so there
is no decision to stamp, and leaving the modal up lets the human retry instead of dismissing on a
silent failure.

Version 0.43.23 / build 177.

---

## 0.43.24 — the password path is measurable from the log (build 178)
2026-10-27

Two changes, both about stopping the guesswork.

**The password approve now stamps.** `finishWithPassword` called `finish()` directly instead of
`showStamp`, so the sudo modal was the ONE decision that dismissed instantly with no ink mark and no
thud (Mido, 2026-10-27: *"no fucking stamp sound and immediately dismissed"*). It now resolves,
wipes the password, and calls `showStamp(approved = true)` like every other path. A resolve that
fails no longer finishes the Activity either — nothing was approved, so there is nothing to stamp,
and the modal stays up for a retry.

**The password is now measurable instead of arguable.** A rejected password and a mangled password
look identical from the outside. `SshPlugin` now logs the LENGTH and a SHA-256 prefix of the chars it
received — never the value, and the digest cannot be turned back into the secret. One line in
`/logs` answers "did the right password arrive" without asking the human to re-type it and guess.

That is the actual lesson from this session: compiles and a Taildrop exit code say nothing about
what the phone did, and three rounds were spent patching what a single log line would have named.

Version 0.43.24 / build 178.

---

## 0.43.25 — the stdin buffer was wiped while JSch was still reading it (build 179)
2026-10-27

Mido, 2026-10-27: *"the password is right"* — while sudo kept rejecting it. Both were true.

`SshExec` wiped the password bytes immediately after `channel.connect()` returned, on the assumption
that the data was already on the wire. It is not: JSch copies stdin to the remote side on its OWN IO
thread, which `connect()` merely starts. So the array was zeroed while JSch was still reading from it,
and sudo received NULs where the password should have been — which fails authentication for a
password that was typed perfectly.

The stream reference is now kept alive for the whole command and the wipe happens in the `finally`,
AFTER `channel.disconnect()` stops JSch reading. Safe and correct, in that order.

**Why this took several rounds.** A rejected password and a mangled password are indistinguishable
from the outside — both answer "Authentication failed". 0.43.24 added a length + SHA-256 prefix to
the log so the difference is visible; this release fixes the cause that diagnostic was added to find.

Version 0.43.25 / build 179.

---

## 0.43.26 — the stamp animates, and the loader stays gone (build 180)
2026-10-27

Mido, 2026-10-27: *"i saw a fucking 1ms flash of the stamp"* and *"don't show loading after
clicking the button just the animation"*. Both were real.

**The stamp ran with no geometry.** `stamp()` started the animation from `post {}`, which is not a
layout callback — it runs after the next traversal, which can happen before this view has been
measured. `radius` is derived from `width`/`height`, so it evaluated to its `1f` floor, the overlay
drew nothing visible, and the whole animation completed in a few milliseconds. That is the flash.
It now starts from `onSizeChanged`, which only fires when real dimensions exist, with an immediate
start if the view is already laid out.

**The loader came back behind the stamp.** The 120ms tick kept running after the decision and called
`applyArmed(false)`, which re-showed the indeterminate bar — so the animation played over a
"loading" indicator. Two changes: the tick now stops entirely once `stamping` is set (after a
decision it can only do harm), and `showStamp` hides the bar outright. `armBar` had to become a
field for that; it was a local in `onCreate`.

Version 0.43.26 / build 180.

---

## 0.43.27 — the stamp sound stops being a beep, and nothing fails silently (build 181)
2026-10-27

Mido, 2026-10-27: *"the sound is back to a fucking peep"* then *"i said a million times no fucking
silent fucking fallbacks / remove all now you fuck and fail hard"*.

**Why it beeped.** `SoundAlerts` fell back to a `ToneGenerator` knock whenever the sample was not
decoded. That fallback was not rare — it was EVERY time, because `setOnLoadCompleteListener` was
registered AFTER `load()`. `load` is asynchronous and could complete before the listener existed, so
`stampLoaded` stayed false forever and every stamp took the beep path. The listener is now registered
first.

**The fallback is gone, and so is the silence.** Both were the same mistake: substituting a different
outcome for the real one. `SoundAlerts` now throws `SoundUnavailable` when the pool cannot be built,
the sample will not decode, `load` returns 0, `play` returns 0, or there is no vibrator. Nothing is
caught and dropped inside the file. `preload()` runs from `BridgeService.onCreate`, so the sample is
decoded long before the first decision.

**Failures are visible now.** Both stamp call sites catch `SoundUnavailable`, log it at ERROR with
the cause, and the modal shows a toast. The overlay's own failure was logged as a WARNING and is now
an ERROR — that is the line that would have named the 1ms-flash bug the first time it happened.

Version 0.43.27 / build 181.

---

## 0.43.28 — grain reaches the buttons (build 182)
2026-10-27

Mido, 2026-10-27: *"some ui buttons and elements still dont have the grain texture / EVERY FUCKING
ELEMENT MUST HAVE IT"*.

**Why buttons were flat.** They are `MaterialButton`s painted with `backgroundTintList`, which is
deliberate — a tint preserves the ripple, the pressed/disabled state and the corner radius, and
replacing the background with a drawable drops all three. But a tint paints over the whole control,
so grain placed in the background could never show through. Grain and the drawn edge now go in the
button's FOREGROUND as a `LayerDrawable`, over the tinted background. The ripple still fires
underneath because the foreground layers are not clickable.

**The progress button** sets its own background (a two-tone track plus a draining fill), so grain is
stacked as a third layer there.

**`GrainCoverageTest` now scans `backgroundTintList` too.** It was checking for
`setBackgroundColor`/`GradientDrawable`/`ColorDrawable`, which is why the buttons slipped through —
the tint was the thing making them flat, and the test did not know the word. The allowlist names each
remaining exemption with its reason: a 3dp risk strip, a 10dp oval dot, the progress track and fill
(both under the grain layer), and the three tint assignments (grain is in their foreground).

Version 0.43.28 / build 182.

---

## 0.43.29 — the stamp is actually drawn (build 183)
2026-10-27

Mido, 2026-10-27: *"I saw nothing, just a slightly dimmed modal"*. Correct, and the cause is one
line.

**A 0..1 value was passed where 0..255 was expected.** `stampAlpha` is a Float in 0..1;
`colorWithAlpha` takes an Int in 0..255. So `colorWithAlpha(ink, stampAlpha.toInt())` produced alpha
0 or 1 — a fully transparent disc. The scrim is a fixed constant, which is why the screen dimmed
while no ink appeared. Every alpha in `onDraw` now goes through a single `a255` conversion, and the
verdict word is drawn in opaque paper so it reads as punched out of the ink rather than fading with
it.

This is the fourth distinct bug in this one animation (teardown race, layout timing, loader
overdraw, and now alpha scale), which is the real lesson: an effect that "does not show" was checked
for *whether it ran* instead of *whether it drew anything visible*. A pixel assertion would have
caught all four.

Version 0.43.29 / build 183.

---

## 0.43.30 — green approve, see-through grainy ink, snappier slam (build 184)
2026-10-27

The stamp finally works, so this is the first release that could be about how it FEELS rather than
whether it appears. Mido, 2026-10-27: *"make it a little transparent and grainy / and make approved
green not red, keep reject red / snappier animation"*.

**Green for approve, red for deny.** `ink` is now derived from the verdict and reuses
`Theme.SUCCESS` / `Theme.DANGER`, so the stamp agrees with the button the human just pressed instead
of contradicting it.

**Slightly transparent.** Overall ink opacity is 0.88, applied once in the `a255` conversion. A real
stamp on paper is uneven and lets the sheet through; a fully opaque disc reads as a sticker.

**Grainy.** The shared `PaperGrain` tile is clipped to the ink disc and painted over it. This is the
same texture as every surface, which is the point — the stamp is ink ON that paper, so it should
carry the same fibre.

**Snappier.** Slam 220→150ms, fade-in 60→45, tilt 340→220, bleed 420→260 (delay 200→130), settle
500→260 (delay 320→230), fade-out 260→180 (delay 820→560). Total ~1.5s → ~1.0s: the thunk lands
sooner and the whole gesture clears before it can feel like a wait.

Version 0.43.30 / build 184.

---

## 0.43.31 — paper creases when the modal arrives (build 185)
2026-10-27

Mido supplied a recorded paper-creasing sample and was explicit about where it belongs:
*"use this when modal appears"*, then *"nooo not the stamp"*, then *"modal appear only"*. Arrival and
verdict are two different moments, so they are two different sounds.

`res/raw/crease.ogg` is seconds 1–5 of that recording (exactly as asked), faded 40ms in and 100ms out
so it does not click. `SoundAlerts.modalAppear` plays it at 0.7 volume from `ApprovalGate` when the
prompt is posted — the stamp keeps its own thud on the decision.

The two samples share one `SoundPool` and one load listener, which now marks whichever id finished
rather than assuming the stamp. Both are decoded at service start via `preload`.

Fail hard, unchanged: `modalAppear` throws `SoundUnavailable` if the sample is not decoded or will
not play, and the caller logs it at ERROR. No substitution, no silence by accident.

Version 0.43.31 / build 185.

---

## 0.43.32 — the stamp is the supplied artwork (build 186)
2026-10-27

Mido supplied two finished stamps (2026-10-27: *"use those pngs instead for the stamp, keep animations
and 3d and sounds just remove old text or material and use those"*). The hand-drawn ink disc and the
punched-out lettering are gone; the pixels are his.

**Keying the black.** The source files are JPEG, which has no alpha and never stores true black — the
background sits around 5-25, not 0. The first key treated anything above zero as opaque, which left
55% of the APPROVED image at near-zero alpha: it would have looked washed out. The key is now a ramp
from 28 (fully transparent) to 70 (fully opaque), with the colour rescaled so a keyed pixel keeps its
hue at full opacity. Verified by compositing both over the app's paper colour: no halo, no black box,
ink detail intact. 60% transparent / 38% opaque on APPROVED, 80% / 19% on REJECTED.

**Everything else is unchanged.** The slam scale, the 3D tilt through `android.graphics.Camera`, the
fade in/out, the ink opacity and the thud all still apply — only the drawn geometry was replaced by
`canvas.drawBitmap`. Each stamp fits to its own aspect ratio, so the wide APPROVED mark and the tall
REJECTED mark both sit correctly.

Version 0.43.32 / build 186.

---

## 0.43.33 — the modal wears the app icon, once (build 187)
2026-10-27

Mido, 2026-10-27: *"replace lock emoji in modal with the icon and remove it from down"*.

The header read `🔐 awaiting your stamp` while a separate 56dp launcher icon sat in the pinned
bottom block — the same identity in two places, one of them an emoji. The header is now a 40dp app
icon beside "awaiting your stamp", and the bottom row is gone. The unused `iconRow`/`icon` locals
were deleted rather than left to trip `-Werror`.

Version 0.43.33 / build 187.

---

## 0.43.34 — a bigger, tilted, darker stamp (build 188)
2026-10-27

Mido, 2026-10-27: *"make stamp slightly larger, slightly rotated and darker"*.

**Larger.** The fit went from 0.82 x 0.42 of the view to 0.94 x 0.50. At the old size the artwork
read as a badge rather than a stamp.

**Rotated.** -6 degrees, applied to the canvas and pivoted on the stamp's own centre so it cannot
walk off-screen. Done in the canvas rather than baked into the PNGs because the REJECTED artwork is
already tilted in the source and APPROVED is dead level — the angle gives both the same hand-placed
feel.

**Darker, by multiply not by alpha.** Lowering alpha would have made the stamp see-through, which is
the opposite of darker. A `PorterDuffColorFilter` in MULTIPLY mode with a neutral grey (0xC8) takes
about 22% of the brightness from every pixel while leaving the artwork's grain and edge falloff
intact. The filter's alpha stays at 255 so it does not re-key the transparent background.

Version 0.43.34 / build 188.

---

## 0.43.35 — the log can be cleared, and it can no longer grow without limit (build 189)
2026-10-31

Mido, 2026-10-31: *"nuke, and make sure it doesn't grow forever — limit by count, date or size"*.

**There was no way to clear the log at all.** `AuditStore.clear()` existed but nothing called it:
no route, no button. So "reset the log" was a manual phone-side delete or nothing. Added
`DELETE /audit`, which is a `DELETE` verb so it gets its own policy key and its own approval card —
the card is the only place a human sees "this wipes the log" before it happens. It returns
`deletedDayFiles`, and that count is the evidence: a `0` is deliberately ambiguous (already empty
vs. nothing could be deleted), so it is reported alongside `recordsRemaining` rather than as a
success flag. The removal is checked per file; reporting "cleared" over a file that would not
delete would make the caller believe history was gone when it was not.

**Retention only ever ran at process start.** `prune()` was called from the constructor and nowhere
else, so a long-lived bridge — the normal case, since the service runs for days — appended forever
and enforced nothing until the next restart. That is the actual "grows forever" bug: the bounds
existed and were correct, they just never ran. `append()` now calls `pruneIfNeeded()`, which stats
bytes and day files (O(days), single digits) and only pays for a full prune when a bound is
actually crossed. Enforcing on every write must not mean re-reading every line on every request.

**A third bound: `maxLines` (5000).** Age alone lets a burst pack an enormous amount into seven
days; the size cap alone lets an absurd number of tiny records live under 20 MiB, because a
rejection record is a few hundred bytes. All three act on WHOLE DAY FILES, oldest first, and the
newest file is never deleted — a day cannot be trimmed in half when the file name IS the date, so
one very busy day can sit above the cap, bounded by that single day rather than by growth forever.
The count is tracked in `storedLines` and deliberately not read from `index.size`, which is capped
at `DEFAULT_MAX` for the list view and would therefore silently stop enforcing above 2000.

**`unreadableCount()` no longer moves when nothing changed.** `line()` — a pure read that runs every
time a detail row is opened — incremented the corruption counter on a failed file read, so browsing
the log inflated the number that reports corruption. Reads do not measure; `load()` does. That is
also why `audit.unreadableLines` could not be trusted as "7": it had four causes (unreadable file,
unreadable line, bad JSON, unknown outcome) behind one int, and one of them was a side effect of
looking.

`/debug` now also reports `audit.maxLines` next to `maxBytes`/`maxAgeDays`, so the three bounds are
visible from the same place.

**3 new tests**, all in `AuditLogTest` (10 → 13): the count cap drops the oldest whole day, the cap
never deletes the newest day even when it alone exceeds it, and reading a line does not inflate the
counter. `clear()`'s new return value is asserted as `1`, not ignored.

Version 0.43.35 / build 189.

---

## 0.43.36 — the hint card remembers where you left it, per orientation (build 190)
2026-10-31

Requested as a backlog item: *"the NOTIFY card can be dragged, and the position resets to the
default corner on every new card"*.

**It did not persist at all.** The anchor was two in-process ints set to `dp(14)` on first use, so
the position survived only until the process died — and the service is deliberately long-lived, so
"every new card" and "every reboot" were the same bug seen twice. Now saved to
`SharedPreferences` on drag-end.

**Per orientation, because one key would be a bug that looks like a feature.** The card is anchored
bottom-left, so a landscape position restored into portrait is a coordinate the user never chose.
`CardAnchorStore` keeps `portrait`/`landscape` separately, and the in-memory anchor carries the
orientation it belongs to — without that, a rotation would keep the old anchor and the per-
orientation store would only ever be read after a fresh start.

**One clamp rule, used by drag AND restore.** The old clamp lived inline in the drag handler, so a
restored value would have bypassed it — and a position saved on a wider display would come back
off-screen. In `NOTIFY` mode silence approves, so a card nobody can reach is a request approved by
nobody: this is correctness, not polish. `CardAnchor.clamp` is pure and total, and it floors the
ceiling at `minEdge` — a card taller than the screen collapses to the edge rather than handing
`coerceIn` an inverted range, which throws.

**Height is clamped twice, on purpose.** The card's real height is only known after layout, so the
attach pass clamps X against a known width and Y against height 0; a post-layout pass re-clamps both
with the measured height, which is what catches a long AI line growing the card off the bottom edge.

An unset anchor loads as `null`, never a default position — a default is indistinguishable from a
saved value and would place the card where nobody put it.

**No reset button.** Mido, 2026-10-31: *"no need for reset"*. Dragging it back is the reset.

**5 new tests** (`CardAnchorTest`): an in-bounds position is kept exactly, a position from a wider
display is pulled back to the edge, a card larger than the screen collapses instead of throwing,
portrait and landscape keep separate anchors, and an unset anchor is null rather than a default.

Version 0.43.36 / build 190.

---

## 0.43.37 — the AI backend is a choice: off, local, or a box on the tailnet (build 191)
2026-10-31

Backlog item: *"Off = no summary and no risk, deterministic rows only (must not 503 the bridge).
Local = the on-device `.litertlm` (current). External = Ollama or unsloth server over the tailnet."*

**`aiEnabled: Boolean` became `aiBackend: OFF | LOCAL | EXTERNAL`.** "The AI is on" stopped being
one thing the moment a remote backend became possible: LOCAL needs a model file on disk, EXTERNAL
needs a host that answers, and they fail in different ways. A boolean would have had to mean one of
them and hide the other behind a second flag that could disagree with it — two settings that can
contradict each other is how a bridge ends up refusing everything for a reason nobody set.

**`serves()` asks the BACKEND, not the model registry.** This was the load-bearing part. The old
check was `ModelRegistry.readiness(ctx).ready`, which for EXTERNAL would have been permanently
false — the build would have answered `503 model_not_ready` about a model file that path never
loads. Now each backend answers for itself, and `notReadyDetail` names the actual cause instead of
always blaming a missing file.

**A dead box REFUSES the request (Mido, 2026-10-31: *"fail"*).** The AI is core here, so a null
summary already fails the request; quietly dropping to rows-only for this one backend would be the
silent fallback this project forbids. Reachability is probed on the request path (1.5s timeout,
cached 3s so one request does not pay twice) so the refusal lands BEFORE a modal is built, with an
error naming the endpoint and telling the human to start it or switch back.

**The prompt and the parser moved into `AiReply`, shared.** Two copies — one per backend — would
have drifted the first time either was tuned, and the drift would be invisible: both would still
answer, and only the summaries would disagree. The system prompt is the specification and the
parser is the check on it, so they are now one file. The prompt text is carried across verbatim,
comment block included: every clause in it was bought with a specific failure.

**Secrets cannot leak to the external box, by ORDERING rather than by a filter.** The AI step runs
before the broker substitutes anything — `rawBody` is read from the socket at `BridgeService`, the
facts are built inside the approval view, and `***` becomes the real credential only inside the
handler that runs after `ApprovalGate.await`. So what crosses the wire is the literal placeholder.
No redaction pass was added, because none is needed and a filter would have implied the ordering
was not guaranteed.

`EXTERNAL_AI_URL` comes from `local.properties` so a different host needs no code change; empty
falls back to the compiled default. The model tag is a constant (`qwen3:8b`) — the point of this
backend is "bigger than the phone's", and a free-text field would be one more thing to get wrong.

The Options row is three buttons rather than a toggle: three states cannot be expressed as on/off
without hiding which one is active. The EXTERNAL text states the refusal behaviour plainly, so a
human choosing it reads the trade-off there rather than discovering it when the LAN box is asleep.

**`PolicyTest` rewritten for the tri-state** — including that an unknown value falls back to OFF
(the direction that frees memory and never reaches a network box) rather than guessing. Suite: 360
tests, 0 failures.

Version 0.43.37 / build 191.

---

## 0.43.38 — attention cues, the one route that does not ask (build 192)
2026-10-31

Backlog item: *"let agents play sounds to get user attention — a route that plays a short sound so
a request can get attention without a notification. Ships with a small fixed set (a few distinct
cues, not arbitrary audio)."*

**No card, no notification (Mido, 2026-10-31: *"no card or even notification for those"*).** That
makes `POST /audio/{cue}` the only GATE-EXEMPT route in the app, and the reason is not convenience:
a cue exists to make someone look at the phone, so requiring them to look first is a circular
requirement. It is defensible only because the route can do exactly one thing — make a short noise.
`Route.gateExempt` carries that reasoning in the source, including the rule that a future route
wanting the flag and able to read, write or execute is asking for a policy rule, not an exemption.

**It still logs like every other request (Mido: *"it still logs as other requests"*).** A normal
`APPROVED` record — no new outcome value, no marker. A private outcome would have made the log stop
being one list of requests: a reader would have to learn a second vocabulary to understand what the
phone did.

**The exemption is a branch, not an early return.** The gate block was wrapped rather than
short-circuited, because the handler-call site below it is deliberately single: the comment there
records that an early-returning ungated branch is exactly how `Route.auditResponseBody` once ended up
honoured on one path and ignored on the other. A second exit would have re-opened that.

**Three cues, closed set.** `ping`, `chime`, `alarm` — a name from a fixed list, so the worst a
caller can do is choose which of three sounds plays. No file, no URL, no duration or volume. A route
that took audio would be a speaker for hire.

**One shot (Mido: *"one shot"*).** A single `SoundPool.play` with loop 0 — no repeat, no stop route,
no state to clear, so nothing can keep making noise after the request that asked for it finished.

**Refused when the screen is off.** A cue on a locked dark phone is noise in a pocket that nobody
can act on, which is the same rule `SoundAlerts.notifyTick` already follows. Answering rather than
silently skipping is deliberate: a caller that gets 200 must be able to believe a sound played.

**The assets are real recordings, not generated tones (Mido: *"don't generate get assets online"*).**
The first pass synthesized three sine waves with ffmpeg; that was wrong — an unattributable,
unreproducible asset with no licence to check. Replaced with three files from Kenney's "Interface
Sounds" 1.0 pack (CC0, public domain), converted to Ogg Vorbis with no other processing. The
provenance is recorded in `assets/cue-attribution.txt` naming the original file per cue, so a future
reader can verify the terms rather than guess.

**The test suite caught a real bug this introduced.** `ErrorCodesTest` scans the source for every
`PluginError("...")` code and fails if one is unclassified. The cue route throws `denied` on a dark
screen — and `denied` was never in `ErrorCodes.MAP`, because the gate always constructed that 403 by
hand and no plugin had ever thrown it. A plugin throwing it would have answered **500** and been
logged as a server fault. Now classified as 403, with the history recorded at the entry.

Version 0.43.38 / build 192.

---

## 0.43.39 — the bridge binds any reachable address, not just a tailnet one (build 193)
2026-11-01

Tailscale was a hard requirement, and nobody had decided that.

`tailnetAddress()` returned an IPv4 only in `100.64.0.0/10` and `null` for everything else, and
`BridgeService` treated `null` as "refuse to start", retry on a ladder, then sit waiting. So a
phone on ordinary Wi-Fi could not serve at all — and the only evidence was a notification telling
the human to open Tailscale. A recommendation had become a precondition inside a helper whose name
did not say so. Mido, 2026-11-01: *"tailscale only offers an ip over internet, in theory if the
bridge is using the same network it should still work without tailscale at all."*

**The rule moved into `core/BindAddress`, a pure object**, so it can be asserted without a device —
the same extraction `HostAllowlist`, `ResponseBody`, `StartupRetry` and `VaultState` already get. A
bind decision buried in an interface loop is a decision nobody can test.

Order: prefer the tailnet (the only address that survives leaving the network), then `wlan`/`eth`,
then anything left. Rejections are the interesting half:

- **loopback** — reachable only from the phone itself, so binding it makes the bridge look alive
  while being unreachable from the agent;
- **link-local `169.254/16`** — that is a failed DHCP lease, not an address;
- **IPv6** — the server binds IPv4;
- **cellular (`rmnet`/`ccmni`/`pdp`/`seth`)** — a valid IPv4 that no inbound connection can reach,
  because the carrier NATs it. Binding one reports success and every caller times out, which is
  the worst shape a failure can take.

Null now means only "the phone has no reachable address" (airplane mode), and the caller still
treats it as wait-never-bind-`0.0.0.0`: a wildcard bind is a decision nobody made.

`BridgeStatusNotice.WAITING_FOR_TAILNET` became `WAITING_FOR_ADDRESS`, and the copy lost Tailscale
as a requirement — "It will start by itself as soon as the phone is on a network", and the stopped
state says "Check Wi-Fi or Tailscale" because Tailscale is now one option among two.

**Tailscale is now a recommendation made once, on the onboarding screen** ("recommended so your
agent can reach the phone from anywhere; without it, your agent must be on the same network").
No toggle, no conditional ladder, no rebind.

**Two things accepted as debt, deliberately.** Anyone who can reach the phone's address can reach
the bridge, and `/help` is exempt — so route names, secret instance names and allowed hosts are
readable from that network without a tap. Real operations still require a tap. Hardening comes
after open source.

**10 new tests** (`BindAddressTest`, 360 → 370), including the regression this change exists for:
a Wi-Fi address alone now returns an address instead of null. Also pinned: the tailnet range
boundaries are exact at `100.64` and `100.127/128`, since an off-by-one there either steals a
carrier address or refuses a real tailnet one.

Version 0.43.39 / build 193.

---

## 0.43.40 — GET /help: the agent's entry point, and its update mechanism (build 194)
2026-11-01

The first piece of the agent-facing distribution design. `GET /help` describes this bridge and
everything it can do, from the RUNNING build — nothing is restated from memory or from a document,
which is why it cannot describe a route this build does not have.

**A normal gated plugin, not an exemption.** It ships with a seeded `help/ANY -> NOTIFY` rule, so a
first call costs a 5-second card rather than a full-screen modal. NOTIFY is still the floor: the card
appears, silence approves, the call is audited like every other one. The rule is seeded as DATA, so
it appears on the exceptions screen where the human can see and change it — a hidden default that
nobody can find is the kind of thing this app refuses to do.

**Fresh installs now default to AUTOMATIC, not SECURE.** AUTOMATIC is the only mode that READS the
rule table, so it is the only one where a default rule means anything — under a forced SECURE or
NOTIFY every rule is inert, and the seeded rule above would be a line that never fires. This does
not weaken the gate: `AUTOMATIC` with no matching rule still resolves to `SECURE`, so "silence means
ask me" is preserved, and a rule can never auto-approve because no such mode exists.

**One builder, two renderers.** The JSON fields and the `markdown` string are produced from the same
objects in the same pass, so the prose cannot drift from the data. `markdown` is a FIELD, not a
second response shape: a `?format=md` branch was written and then removed — it bought a Content-Type
and nothing else, at the price of two shapes on one route, which is where a bug hides and the path
nobody tests as often. A caller that wants the raw text does `jq -r .markdown`.

**The markdown carries FACTS, never advice.** "`denied` — no approval was recorded" is a fact about
the code. "Do not retry a `denied`" is judgment, and judgment belongs in the agent skill. Keeping
that line clean is what stops this route from becoming a second, diverging skill.

**`ErrorCodes.MEANINGS`** — 55 codes, one factual line each, exact match with `MAP`. This exists
because the status map alone cannot be used: knowing `denied` is 403 does not say whether a human
refused or the phone was in a pocket, and those two demand opposite responses. Guarded exactly as
`MAP` is — a code with no meaning, a meaning with no code, or a blank meaning fails the build.

**`contractVersion`**, separate from the app version and from `appBuild`. It moves only when the
agent-facing surface moves; cosmetic releases leave it alone, so an agent's cached understanding
survives a stamp change. `HelpContractTest` pins the route set (34 routes, scanned from source
because most plugins need a Context the JVM suite does not have) — adding or removing a route
without bumping fails with a message naming both edits. It also pins that the audio cue is the ONLY
gate-exempt route, so a second exemption has to be argued for rather than quietly added.

**What `/help` deliberately omits:** token values — the metadata path never reads them, so that is
structural rather than a filter, and no parameter can ask; and the gate's current state, because
capability is describable and policy is not. `gmail/DELETE` being NOTIFY tells a reader which of your
operations is least protected.

**3 new tests** (`HelpContractTest`), 371 -> 374.

Version 0.43.40 / build 194.

---

## 0.43.41 — callers must be named before they can call (build 195)
2026-11-01

The build box's tailnet IP was compiled into every APK in three places, and the "label" for a
caller was a fallback in disguise: match one hardcoded address, otherwise echo the raw IP. A reader
could not tell a machine named `192.168.1.5` from one that had never been named at all.

**A caller registry replaces the guess.** `callers.json`, keyed by address, states `verified(name)`
or `denied`. **Absent means unverified, and unverified is REFUSED** (Mido, 2026-11-01: *"refused
until named"*) — so the registry is an allow rule, not a label. The check runs at the TOP of
dispatch, ahead of route resolution: an unnamed address must not learn which routes exist by
watching whether it gets 403 or 404.

**Naming is a full-screen prompt triggered by the caller's own first request** (`CallerActivity`,
same shell as the approval modal, own class — `ApprovalView` is fifteen fields describing a request
to approve, and a naming prompt fits none of them). A name is required to allow, and the answer is
recorded, so a stranger cannot make the phone light up repeatedly: they are offered once, and either
named or blocked.

**Deny is offered, and needs a fingerprint too** (Mido: *"yes"*). A one-tap block would let someone
holding an unlocked phone block the owner's own laptop. Denying writes `denied`, and that address is
then refused SILENTLY with no prompt on every later call — the record is what makes blocking
cheap instead of a re-prompt every knock.

Loopback resolves to "this device" inside the registry, so the app's own probes never depend on a
human naming them.

**Lenient parse that DROPS rather than defaults.** An unreadable row makes the address unverified
again. Defaulting to `verified` would let a corrupt byte grant access; defaulting to `denied` would
lock out a host the human approved. Neither is a decision a parser may make.

**The two hidden fallbacks are gone.** `ExternalModel` had a default endpoint pointing at a specific
private machine, so "external AI not configured" silently meant "send this request to someone
else's box" — now an unconfigured build reports exactly that and refuses. And `BridgeService` held a
DUPLICATE of the caller-label function with the same IP inlined a second time; both are deleted, and
`AuditDraft` now requires a label with no default, because only the registry knows the difference
between a name and an address.

**Options gains the caller list**, so a block can be undone and a misheard name fixed without
editing JSON. "Forget" removes the row entirely, which means unverified again — the only way back
is through the same prompt everyone else came through.

**Three compiler catches worth recording:** `BiometricPrompt` needs a `FragmentActivity`, not an
`Activity`; Kotlin's `Any` has no `wait`/`notifyAll`, so the one-prompt-at-a-time lock had to be a
`ReentrantLock`; and `setPriority` is deprecated under `allWarningsAsErrors` — the channel already
carries `IMPORTANCE_HIGH`.

374 tests, 0 failures.

Version 0.43.41 / build 195.

---

## 0.43.42 — a blocked gate names its cause, and every text input is readable (build 196)
2026-11-01

Two defects with the same shape: the app knew what was wrong and told the human something else.

**The gate said "set up a fingerprint" for every reason it was blocked.** The check returned a
`Boolean`, so `canUseBiometric()` collapsed four unrelated causes — nothing enrolled, temporarily
locked out, hardware unavailable, vault key invalidated — into `false`, and the modal printed one
line for all of them. So a human whose vault key had been invalidated by a new fingerprint was sent
into Settings to add a fingerprint they already had, and a two-minute lockout read as "your device
cannot do this". Both left APPROVE dead with no way forward, and Mido's report is the design rule:
*"the gate should offer to unlock so the user doesn't click approve on a broken gate like a fool."*

`BioStatus` now names the cause and the copy matches it: no fingerprint → go to Settings; lockout →
it clears by itself, so wait rather than reconfigure; vault key invalid → reset the vault in
Options, which is the only thing that can actually fix it. A hardware fault is deliberately NOT
reported as a lockout — telling someone to wait for something that will never clear is worse than
saying it is broken.

`NeedsUnlock` was written and then deleted: a crypto-bound prompt IS the unlock, so that state can
never occur. A state that cannot be reached is a fourth cause the UI must be trusted not to print.

**Every `EditText` in the app left text colour to the platform, and the platform's default is
white-ish** — invisible on this app's cream background. Fourteen inputs across four screens: a sudo
password, a secret value, a host name, a caller name. The value was there and unreadable, which is
worse than a missing field because you retype it and blame the keyboard. The default comes from the
launch theme, and this app builds every screen programmatically, so nothing had ever set it.

All fourteen now state `setTextColor(ON_SURFACE)`, `setHintTextColor(ON_SURFACE_VARIANT)` and
`highlightColor = ACCENT` explicitly, and the reason is recorded on the `ON_SURFACE` constant so the
next input added inherits the rule rather than the bug.

374 tests, 0 failures. Input contrast is not unit-testable — it needs the device.

Version 0.43.42 / build 196.

---

## 0.43.43 — GET /probe: prove the gate reaches you, with no setup (build 197)
2026-11-01

Every real route has a prerequisite that may not exist yet: Gmail needs a login, `/notifications`
needs the listener permission, Drive needs a scope, a secret route needs a vault entry, SSH needs a
connection. So a first-run check built on any of them fails for a reason that has nothing to do with
the gate — and the human concludes the gate is broken when it is only unconfigured.

`GET /probe` has **zero dependencies by construction**. No Google account, no vault, no permission,
no model. If it answers, the gate answered.

**Approve passes AND deny passes** (Mido, 2026-11-01: *"the gate should offer to unlock so the user
doesn't click approve on a broken gate like a fool"* — the same instinct, one level up). The claim
being tested is "a human is asked and the answer is honoured", not "the request runs". A `403 denied`
here is the product working: the gate refused and the handler never executed. Treating a denial as a
failed probe would teach someone to approve everything on reflex, which is the opposite of the
point.

`aiSummary = false`, because the AI is core and the model is a ~474 MB download a fresh install has
not made yet. A probe that waited for the model would answer `503 model_not_ready` at the exact
moment it matters most.

**It stays forever** (Mido: *"keep it just in case"*), as the HUMAN liveness check. `/status` remains
the machine one: ungated, 404 means alive, safe to poll. The split is deliberate — a gated route
must never be a watcher target, which is the bug that produced 523 approval cards in six days.

Adding a route moved `contractVersion` to 2, and the guard caught the pin: `HelpContractTest` pins
the route set, so a new route without a bump fails the build with a message naming both edits. That
is the guard doing exactly what it was built for on its first real outing.

Version 0.43.43 / build 197.

---

## 0.43.44 — a stuck NOTIFY card can no longer strand itself, and the queue stops refusing bursts (build 198)
2026-11-01

Mido, 2026-11-01: *"I have many notifications, so I moved them and now they are stuck. Progress
halted, can drag them but can't dismiss them."* Two defects behind one symptom.

**The card that would not die.** `teardown` set `removed = true` BEFORE calling `removeView`, then
swallowed any exception. So a single failed removal — a window the platform had already detached, a
WM refusing mid-frame — left a card on screen that every later path skipped, because the entry said
it was already gone. The card stayed: draggable, since its touch listener lives on the view, but
undismissable, since the dismiss path reaches `settle()`, which early-returns on a settled entry.
The countdown froze for the same reason: `teardown` had already cancelled the tick.

The flag now follows the OUTCOME. A failed removal keeps the entry live with its window reference,
and a `IllegalArgumentException` ("view not attached") counts as SUCCESS rather than a failure to
retry — the platform dropping it is the outcome we wanted.

**And something has to retry.** `sweepStranded` runs before every new card and re-runs itself every
2s while anything is still stuck, because the common case is a burst that drains — the LAST card is
the one that gets stranded, with nothing behind it to trigger a sweep on presentation. It is capped
at 30 attempts: a self-reposting runnable with no exit is a leak, and if the window manager will
never release the view, polling forever buys nothing. At the cap it stops and says so in the log,
which is the only place an unremovable window can be understood from.

**The queue was refusing the burst.** `MAX_WAITING = 3` meant every request past the fourth was
`Admission.Refused` before any card was drawn — from the human's side, indistinguishable from
"progress halted". A waiting card costs one Entry and no window, so the real constraint is patience,
not memory, and `NotifyGate` already sizes its deadlock escape hatch from `MAX_IN_FLIGHT`. Raised to
8: the line is now 9 deep, and the escape hatch scales with it automatically because it was derived
rather than hardcoded.

374 tests, 0 failures. The stranded path is not unit-testable — it needs a window manager that
misbehaves — so it is verified by construction and by the log line at the cap.

Version 0.43.44 / build 198.

---

## 0.43.45 — an SSH key is not a broker secret, and /help can list SSH targets (build 199)
2026-11-01

`GET /help` advertised `POST /secrets/ssh-evox2/call` — a route that could never succeed, because
an SSH connection has no allowed hosts, so `host_not_allowed` fires pre-gate forever. It also told
a caller "there is an HTTP API called evox2", which is simply false.

**One vault holds both kinds**, so iterating the name index registered a broker plugin for every
entry. `SecretMeta.kind` already distinguishes `SECRET` from `SSH` — it exists so the Secrets
screen refuses to reveal a private key — and registration now checks it. An unreadable entry is
skipped rather than guessed at, because a metadata read that throws must not silently produce a
route either. The route is now genuinely absent: a caller gets `404`, not a `403` implying it
exists.

**`/help` gained `sshConnections`.** SSH targets live in the vault, not `PluginRegistry`, so they
appeared in no route list — a caller had to GUESS a connection name, and a wrong guess is
`404 not_found`, which reads as "the route is broken" rather than "that name does not exist".
Names only: the private key never leaves the vault, and `/help` is reachable by any verified
caller.

Adding a key to the contract surface moved `contractVersion` — the `HelpContractTest` pin forces
the route table and the version to move together, which is the whole reason that guard exists.

A correction to something asserted earlier in the session and never checked: SSH connection names
**cannot** collide with secret names. `vaultNameFor()` prefixes every SSH entry with `ssh-`, so an
SSH key called `todoist` is stored as `ssh-todoist`. The claim was wrong and no fix was needed for
it.

Version 0.43.45 / build 199.

---

## 0.43.46 — /help stops dropping fields it builds (build 200)
2026-11-01

`sshConnections` shipped in 0.43.45's markdown and never appeared in its JSON. The builder produced
it; the response did not carry it.

`respond()` copied four keys BY HAND — `markdown`, `stmp`, `errors`, `plugins` — so any field added
to `describe()` afterwards was silently dropped. The prose and the data disagreed, which is exactly
the drift that having one builder is supposed to prevent: the builder was right and the hand-written
copy was not.

It now copies every key `describe()` produces. A list of names beside a function that returns
objects is a second source of truth, and the one nobody updates.

Found by calling the installed build and comparing the two renderers, not by reading the code —
which is the only way this class of bug surfaces.

Version 0.43.46 / build 200.

---

## 0.43.47 — GET STARTED: one copyable prompt that works with any agent (build 201)
2026-11-01

STMP has no UI to speak of: the phone is a server, the agent is the client. So the useful artifact
for someone who just installed it is not documentation — it is a paragraph they paste into whatever
agent they already use, which makes that agent productive with nothing installed.

**The screen carries no API documentation.** Routes, verbs and every error meaning are served live by
`GET /help`. Restating them here would be a second copy that rots — the same mistake `respond()` made
an hour earlier by enumerating keys instead of copying them. What the prompt carries is the part
`/help` deliberately does not: judgment.

The rules it states, each of which cost a rebuild:

- One call costs one approval, so prefer one well-formed request to several small ones.
- `403 denied` means a human declined. Do not retry, do not rephrase, do not route around it.
- `403 caller_unverified` means this machine has never been named — check the phone, then retry once.
- `403 caller_denied` means the machine is blocked. Stop.
- A credential is never sent; `/secrets` routes take the literal `***` and the phone substitutes.
- `GET /probe` proves the gate reaches a human, and **a `403 denied` there is a pass** — the gate
  refused and the handler never ran.

**The address is read from the running service, not typed.** A hardcoded IP would be wrong on any
other phone and changes between Wi-Fi and Tailscale on this one. When the server is not up the prompt
says `http://<start the server first>:8787` rather than rendering an empty host, because a blank one
reads as a typo.

The prompt is a selectable read-only field rather than a label: a human may want to correct the
address by hand, and text you cannot select has to be retyped from a screenshot.

Reached from a `START` button beside the other destinations on the home screen — first in the row,
because it is the only one a new user needs.

374 tests, 0 failures. The screen is UI, so it needs the device.

Version 0.43.47 / build 201.

---

## 0.43.48 — choosing EXTERNAL AI gives you somewhere to point it (build 202)
2026-11-01

Mido, 2026-11-01: *"when ai option is set to external, I expected being able to set the server url!"*
He was right, and the hole was mine.

0.43.41 removed a hardcoded default endpoint — correctly, since it pointed at one specific person's
machine — and left `BuildConfig.EXTERNAL_AI_URL` as the only source. That is a build-time constant.
So selecting EXTERNAL produced a backend that could never be given an address at runtime: the
setting promised a remote server and then refused for want of a value no screen could set. **A
toggle whose ON state cannot be configured is a dead control**, which is the same defect the gate
declared a day earlier with its single `Boolean`.

**`aiExternalUrl` is now a policy preference**, parsed leniently like the others: absent or garbage
means empty, which is a real state — `AiGateway` refuses and names the cause instead of guessing an
address. `ExternalModel` resolves *policy first, build constant second*, so a runtime choice always
wins over a compiled default; the reverse order would make the setting look broken on any build that
shipped one.

**The field only appears when EXTERNAL is selected.** A URL box under a backend that ignores it is a
control that does nothing — the same lie, one level down.

**It validates before saving.** The scheme must be http or https, a host must be present, and a
trailing slash is refused because the chat path appends and would double it. Saving a malformed
address would surface later as "not reachable", which reads as the server's fault rather than the
address's.

Blank is accepted and means "remove the server" — clearing the field has to be expressible, so the
validator rejects only what cannot work, not what is merely unset.

Version 0.43.48 / build 202.

---

## 0.43.49 — the external AI backend can authenticate (build 203)
2026-11-02

Mido, 2026-11-02: *"STMP should accept an optional token."* He was right, and the missing header was
a real gap, not a misconfiguration.

`ExternalModel` set only `Content-Type`. It had **no Authorization header at all**, so no token could
be sent even if one existed. Meanwhile the local model server answers `401` with
`www-authenticate: Bearer` to any request arriving from a non-private address — and Tailscale's
`100.64/10` is not RFC1918, so the same server that needs no auth from this VM demands one from the
phone. Without this field the only ways to satisfy it were to route around auth with a `Host`-
rewriting proxy, or leave the backend unusable.

**`aiExternalToken`** joins `aiExternalUrl` in the policy, parsed leniently like every other
preference: absent or garbage means empty, which sends no header at all — a local server that trusts
the device must not receive an empty `Authorization`, which some servers reject outright.

**The probe carries it too, and that matters.** A reachability probe without auth answers `401` and
would report a healthy server as unreachable — the wrong verdict, and one that sends a human hunting
for a network fault that does not exist.

**The field is masked**, with the transformation applied after `inputType` for the reason
`ApprovalActivity` records: `setInputType` re-derives the mask and silently clears one set earlier.
It is also excluded from autofill and from instance-state saving.

**Stored in `policy.json`, which is app-private but not encrypted at rest.** The vault is the
encrypted store and is biometric-gated per session, which would block an unattended summarise call.
So this is a deliberate trade, recorded in the field's own comment: a bearer token for a LAN model
server lives beside the endpoint that needs it. Do not reuse a token from anywhere else for it.

URL and token save in ONE write, so there is no window where the address is stored without the
credential it needs, and one policy-change record rather than two.

The URL field also gained the shape guidance Mido asked for: `http://host:port`, no trailing slash,
no path, with the two real ports named (8888 Unsloth, 11434 Ollama) and the note that the address
must be one *this phone* can reach.

374 tests, 0 failures.

Version 0.43.49 / build 203.

---

## 0.43.50 — the token field masks, and a 503 stops lying about the model (build 204)
2026-11-02

Two defects found by actually calling the installed build.

**The token field was not masked.** It set `TYPE_TEXT_VARIATION_PASSWORD` and relied on the theme to
derive the transformation. That is the same trap `ApprovalActivity` records for the sudo field:
`setInputType` re-derives the transformation from the type, so anything assigned before it is
silently cleared, and anything the theme does not supply simply does not happen. A credential field
that renders in plain sight is worse than no field, because you type it in front of whoever is
standing there. It now sets `PasswordTransformationMethod` explicitly, LAST, after `inputType`.

**A 503 described the wrong backend.** `model_not_ready` reported
`ModelRegistry.readiness().detail`, which always describes the LOCAL model file. With EXTERNAL
selected and no reachable server, the caller got `503 model_not_ready` with the detail
`"ready (328 MB)"` — a message that contradicts its own status code and sends a human hunting for a
missing model that is present and fine. It now asks `AiGateway.notReadyDetail`, which answers for the
CONFIGURED backend: "the external AI backend (http://…) is not reachable; start it, or switch the AI
backend to LOCAL or OFF in Options".

That is the same class as the gate's single `Boolean`: one field describing two different systems,
so the message tells the truth about the one that is not in use.

Version 0.43.50 / build 204.

---

## 0.43.51 — plain HTTP to a LAN model server is allowed again (build 205)
2026-11-02

EXTERNAL never worked, and the relay was only half the reason. **Android has blocked cleartext HTTP
by default since API 28**, and this app targets 35. There was no `usesCleartextTraffic` and no
`networkSecurityConfig`, so every request to a `http://` backend was refused on the device before a
single byte reached the network.

The failure mode is what made this expensive: `HttpURLConnection` throws `CleartextNotPermittedException`,
`probe()` catches it and returns false, and the caller gets **"not reachable"**. That reads as the
server being down — so the hunt went to socat, then to firewalls, then to Tailscale `Host` headers,
while the server was answering `200` on every other machine in the building.

**`network_security_config.xml` now permits cleartext**, and the file records why rather than leaving
it to a commit message. A `domain-config` cannot solve this: those entries match by hostname and the
host is whatever address the user types, so there is nothing to enumerate at build time. The base
config is the only mechanism that fits a user-supplied endpoint.

The compensating control is scope, not restriction: this app sends exactly one kind of payload over
that connection — a request-fact summary, already redacted before it reaches the backend. No vault
contents, no SMS bodies, no credentials. The token travels only as the `Authorization` header for the
server the user chose.

It deliberately does NOT use `android:usesCleartextTraffic="true"`: that flag is the blunt version and
hides which hosts are affected.

This is the third defect in this chain that reported the wrong thing — after the 401 that was really
auth and the 503 that described the local model. Each one sent the investigation somewhere real and
wrong. The lesson recorded in the file: a network error string is a description of a SYMPTOM, and
"not reachable" must not be trusted as "the peer is down" until the exception type has been read.

Version 0.43.51 / build 205.

---

## 0.43.52 — the summariser stops thinking, and starts answering (build 206)
2026-11-02

Mido, 2026-11-02: *"10s is too slow, is the local model warm?"* Warm — measured 2.7s for 60 tokens,
twice, no cold start. The model was not the problem. **Thinking was.**

The loaded backend is a reasoning model. Left to itself it spends the entire completion budget on
`reasoning_content` and then returns **an empty string** with `finish_reason: "length"`. So the
request paid ten seconds for chain-of-thought and got nothing back — the summary was never
generated, and `AiReply.parse` had empty text to work with.

Measured on the real server:

| | latency | content | reasoning |
|---|---|---|---|
| thinking on | ~10s | **empty** | long, hit the cap |
| thinking off | **1.9s** | one clean sentence | none |

`chat_template_kwargs: {enable_thinking: false}` now goes with every request. A summariser has no
use for chain-of-thought: it is handed a handful of already-redacted facts and must answer in one
sentence. Reasoning is pure cost here.

A **400-token ceiling** is set as well, and the reason is recorded on the constant: the failure this
guards is not truncation, it is a model that thinks forever and returns nothing.

**The model tag was also wrong.** `MODEL` was a hardcoded `"qwen3:8b"`, which is not served by the
configured backend; the server silently substituted its default 27B, which is exactly the model that
reasons before answering. A fixed tag that does not exist is worse than a setting, because it looks
deliberate and quietly lands somewhere else. Not changed here — making it a setting is a separate
decision — but recorded so the next person does not trust it.

Version 0.43.52 / build 206.

---

## 0.43.53 — the model is picked from the server, not invented (build 207)
2026-11-02

Mido, 2026-11-02: *"this was very stupid, i said no hidden fallbacks. we need to make this work for
other setups not just mine. we should fetch available models and fill a select box with them."*
Correct on every count, and the bug was worse than a wrong constant.

**EXTERNAL had never produced a single summary.** It sent `POST /api/chat` with
`"model": "qwen3:8b"`. The configured server implements `/v1/chat/completions` and not `/api/chat`,
which answers **405 Method Not Allowed**; and it never served `qwen3:8b` either. So every worded
route failed twice over and nothing said so — `/notifications` returned `200` with fifty
notifications and no summaries, which looks like a working feature on a quiet day.

That is three hidden fallbacks stacked in one path:
- `MODEL = "qwen3:8b"`, a constant that no server was asked about
- `stored.ifBlank { BuildConfig.EXTERNAL_AI_URL }`, an empty field silently reaching a compiled-in URL
- the server substituting its own model for an unknown tag, so the wrong name was invisible

**`ExternalDialect` is now stored, not assumed.** Two shapes exist and they are not
interchangeable: `OPENAI` (`/v1/models`, `/v1/chat/completions`, answer in `choices[0].message`)
and `OLLAMA` (`/api/tags`, `/api/chat`, answer in `message`). Detection happens once, when the
human presses refresh, and the winner is written to the policy — visible in Options as
"OpenAI-compatible" or "Ollama". Not re-guessed per request: detection costs a round trip, and a
server that blipped would flip the protocol under a request already decided.

**`aiExternalModel` stores the id the SERVER listed.** The Options picker is a spinner filled from
that listing, so the stored value is always one the server offered. It cannot be typed, which is
the point — a typed id is a guess with extra steps.

**Model and dialect travel in ONE setter.** A model id is only meaningful in the dialect it came
from; storing an OpenAI id beside an ellama dialect would send it down a route the server does not
implement. `withAiExternalModel(model, dialect)` makes the wrong pairing unrepresentable.

**Both fallbacks are deleted.** No build constant, no model constant. An empty field is empty, and
`configureProblem` names WHICH piece is missing: no address, never listed, or no model picked —
three different actions that "not configured" used to collapse into one useless sentence.

**The listing is parsed in a pure object, not in the activity.** `ExternalModels.parse` is where a
server renaming a field turns a real list into an empty one, so it is separated from transport and
covered by JVM tests. It also distinguishes two outcomes that look identical in a broken UI:
"listed nothing" (an empty list, worded as an empty server) and "not readable" (an exception naming
the dialect, worded as the wrong server). Collapsing those hides a wrong address behind a message
about missing downloads.

6 new tests, 380 total, 0 failures.

Version 0.43.53 / build 207.

---

## 0.43.54 — refresh reports what the server actually said (build 208)
2026-11-02

Mido, 2026-11-02: *"couldn't list models."* The server was answering `200` the whole time. The
listing worked and the RESULT was thrown away.

`refresh` built its outcome as a `Pair`: `null to message` on failure, and the catalogue itself on
success. The UI then pulled it apart with `outcome as? Pair<*, *>` — which is null for BOTH, because
a success value is a `ModelCatalogue` and not a pair. So `catalogue` was always null and the
message was always "no answer", even against a server that had just listed a model correctly.

**The shape was the bug.** One variable holding either "a result" or "a result plus an error" is a
type doing two jobs, and the cast that separated them was the only thing making it work — a cast
that is silently null when it is wrong. It now uses `Result<ModelCatalogue>`: `getOrNull()` for the
value, `exceptionOrNull()` for the reason. A failure carries its own message instead of hiding in a
tuple that needs unpacking.

This is the second defect in this one button, after the dialect/path mix-up, and both had the same
signature: **a success path that reported failure.** The lesson recorded in the code: when a UI says
"could not", print the exception, not a placeholder — "no answer" was the one word that ruled out
every real cause.

380 tests, 0 failures.

Version 0.43.54 / build 208.

---

## 0.43.55 — the hint card can no longer grow when the summary lands (build 209)
2026-11-02

Mido, 2026-11-02: *"the problem is the hint, just fucking see why would it ever render a full height card and fix it"*.

The question is the fix. A `NOTIFY` card is a `WRAP_CONTENT` `FrameLayout`, so **anything else that
participates in its layout can decide its size** — and the risk tint was a child `View`. That is
the defect that shipped in 0.6.2 (a `MATCH_PARENT` fill under a `WRAP_CONTENT` parent forces the card
to the whole window) and again in 0.25.1 (the same shape, hidden until the AI summary made the strip
`VISIBLE`, reported as *"looked good until summarize finished then it went back to full height
shit"*).

The 0.25.1 fix was not a fix. It made the strip `WRAP_CONTENT` and then **stretched it to the card's
measured height in a `post {}`** — so the tint still depended on the card, the card still had a child
whose geometry was derived from it, and the whole thing still ran through a stale height read one
frame after the text changed. It was the same dependency wearing a hat.

**The tint is now a background LAYER, not a view at all.** `ui/EdgeBand` is a `Drawable` with a
width, rounded on the card's own corner radius. A background layer cannot participate in layout, so
the AI arriving can now change text and paint but can never change geometry — that is a property of
the construction, not a value to keep in sync. The `post {}` stretch is deleted along with the view,
and so is the `entry.riskStrip?.setBackgroundColor(...)` call whose line was the only reason
`GrainCoverageTest` had an allowlist entry for a 3dp strip.

**Enforced by a test, because this shipped twice while the code looked right.**
`NotifyCardLayoutTest` scans the source and fails the build if `NotifyOverlay` ever adds a second
child to the card, and fails if the risk tint returns as a `View`. The rule is a shape in the
source, so the source is where it can be broken.

**Not reproduced on the installed build, and that is recorded honestly.** Firing live requests
(`/gmail/profile`, `/secrets`) against 0.43.54 produced cards measuring 405×78 px at teardown —
including cards whose summary had landed (`summarised in 1672ms: risk=LOW` in the same second). The
one taller card was 405×112, and that was the AI-failure card carrying an extra error line. So the
trigger is narrower than "a summary exists" — but the layout path was a latent size dependency
either way, and it is gone now.

383 tests, 0 failures.

Version 0.43.55 / build 209.

---

## 0.43.56 — POST /tts: the phone says a sentence, and answers when it has (build 210)
2026-11-02

The backlog item, planned in chat first and built on an explicit go. Mido's decisions, in the order
they were asked: *"exempt as long as known ip"*, *"all basic options configureable in plugin
options, including cap limit"*, *"wait until finished"*, *"both should work if screen off"*,
*"serial bounded"*, *"a silent phone is enough"*, *"refuse"*.

**`POST /tts`, body `{"text": "..."}`, `gateExempt = true`.**

**The exemption is bounded by the CALLER, not by the route, and that needed no new mechanism.** The
caller check runs before routing (0.43.41), so an unnamed address is refused before a route is even
resolved. `gateExempt` on this route therefore means "any caller you have already named may speak",
not "anyone at all". A stranger cannot make the phone talk.

**Why exempt at all.** The point is delivering something to someone who is *not* looking at the
phone. A prompt-first requirement would make the feature usable only when it was unnecessary — the
same circularity that makes `/audio` exempt.

**The wait IS the contract.** The route returns only after `onDone`, so `ok` means the phone
finished saying it. `UtteranceProgressListener.onDone` is the only success path: `onStop` is
reported as failure, because an utterance that did not finish is exactly the "ok for something
nobody heard" lie this file refuses to ship. The deadline is `8s + 120ms/char` derived from the cap,
never a fixed number — and a deadline that trips FAILS the request rather than claiming success.

**Serial and bounded, not flush.** A flush would cut off an older utterance, and because the caller
blocks for the whole thing, the interrupted one could only be answered with a success conditional on
timing. A line is duller and it can be trusted. Overflow answers `503 tts_busy` with the count.

**Everything configurable, capped by construction.** `TtsSettings` (cap, rate, pitch, queue depth)
is ONE object on `Policy` rather than four loose fields — this file has already paid for a
positional rebuild silently resetting siblings, and eight copy methods each naming four more fields
is eight more chances to drop one. `withTts` carries every other field by name. Every value is
clamped by `TtsLimits` on read AND on use: a stored `0.0` rate is not worth crashing for and not
worth honouring either. `TtsLimitsTest` (9 cases) covers the cap, the ceiling, the clamps and the
deadline — these are decisions, and a decision only checkable by listening to a phone is one nobody
re-checks.

**`/audio` loses its attendance check in the same commit.** Mido: *"both should work if screen off"*.
It refused on a dark screen on the reasoning that a cue nobody can see is noise in a pocket — but the
case where an attention cue matters most IS a phone in a pocket or face-down, so the route worked
only when it was unnecessary.

**A silent phone is enough, so there is no ON/OFF toggle.** The utterance rides
`USAGE_NOTIFICATION`, so silent/vibrate/DND silence it for free. A second brake that can disagree
with the first is worse than none, and a setting must never make "off" ambiguous.

**Over the cap is refused, never truncated** — `400 text_too_long` naming the actual length and the
cap, checked pre-gate so it costs no engine work. Truncation is the one failure this route cannot
report, because the reply would look identical to success.

`aiSummary = false`: the text IS the description. Asking a model to summarise a sentence a human is
about to hear can only lose information, and a route that went silent because inference timed out
would fail exactly when someone wanted to be told something.

`HelpContractTest` caught both consequences immediately and both were updated deliberately, not
weakened: `CONTRACT_VERSION` 2 → 3, `POST /tts` pinned, and the exempt set is now an exact TWO —
so a third exemption has to argue for itself in that test.

391 tests, 0 failures.

Version 0.43.56 / build 210.

---

## 0.43.57 — GET /contacts: search only, and the log keeps half a number (build 211)
2026-11-02

Backlog item 11, *"Phone contacts plugin — must allow search"*. Mido's one decision on the shape:
*"audit both but keep number half redacted"*.

**Search-only is the security property, not a limitation.** No list-all, no paging, no export — a
route that dumps the address book hands a stranger every number on the phone in one call, while a
route that REQUIRES a query can only answer about names the caller already half-knows. The backlog
wording is the design.

**Two ways a "query" would have quietly become a bulk download, both closed:**

- An empty query is REFUSED rather than treated as "match everything" — a route that returns the
  whole book for `query=` is the export route with extra steps.
- `%` and `_` are `LIKE` wildcards, so `query=%` matched every row. The query is now escaped and
  the selection declares `ESCAPE '\'`. Escaping runs backslash-FIRST, or the backslashes added for
  `%` and `_` would themselves be doubled.

Also: one character is refused (<mark><code>MIN_QUERY = 2</code></mark>) because `a` matches a large
fraction of any real book; results are capped at 25, read one PAST the cap so the reply can say
`truncated: true` instead of presenting a partial list as complete; and duplicate name+number rows
(the mobile + WhatsApp entry every real contact has) are collapsed, so `count` means people.

**`READ_CONTACTS` is a runtime permission and a route cannot ask for one.** A dialog needs an
Activity and a tap; a handler has neither. So the split is forced: the plugin CHECKS and answers
<mark><code>503 contacts_unavailable</code></mark>, and `MainActivity` owns the prompt behind a
"Contacts search — tap to grant" row. Not asked at launch: a contacts prompt with no visible reason
gets denied reflexively, and a denial is sticky enough to need Settings to undo.

**The audit keeps the matches with numbers half-masked — and this needed a new mechanism, not a
flag.** `auditResponseBody` can only say keep or drop, and this route needs neither: the caller must
receive REAL numbers (a search returning masked ones is useless) while the append-only log must not
become an address book of people who never installed this app. So `Route.auditResponseTransform`
builds a DIFFERENT object for the log — same shape, `number` renamed to `numberRedacted` so nobody
can mistake a mask for the real thing — and the caller's bytes are never rewritten. It runs in the
single dispatch site beside `auditResponseBody`, because that flag itself once drifted by being
honoured on one path and ignored on another.

The mask keeps the LEADING half (`+49 151 1234 5678` → `+49 151 12•• ••••`): country and area code
are what make a number recognisable to its owner, while the subscriber digits are the part that
reaches a person. Masking the middle would keep both ends and identify the number almost completely,
which is redaction theatre. Formatting survives, and a string with no digits is returned unchanged
rather than given a mask it did not earn.

`ContactRulesTest` (8 cases) covers the empty query, the one-character query, the odd-length digit
count (rounds UP, because rounding down masks MORE than half and "half" is the promise), and that
no run of four unmasked digits survives into the audit copy.

`HelpContractTest` caught the new route again and was updated deliberately: `CONTRACT_VERSION` 3 → 4,
`GET /contacts` pinned.

398 tests, 0 failures.

Version 0.43.57 / build 211.

---

## 0.43.58 — a denied permission now says so instead of pretending nothing happened (build 212)
2026-11-02

Reported on device: *"i tapped, but I expected to be asked for the permission"*. The log says the
prompt DID appear — `requesting READ_CONTACTS` at 14:36:54.190, the window lost focus 70ms later and
regained it 5.2s after, then `READ_CONTACTS result: DENIED` at 14:36:59.381. The permission is
declared in the shipped APK (`aapt2 dump permissions` confirms it).

So the request worked and the dialog was real. **The defect was what happened after it:** the row
went straight back to "tap to grant", exactly as it had been before the tap. Nothing distinguished
"never asked" from "asked and declined" from "Android will not ask again". That is what made a real
prompt feel like a dead button.

**Three states now, all distinguishable:**

- never asked → <mark><code>tap to grant</code></mark>, warning
- declined, can ask again → <mark><code>declined — tap to ask again</code></mark>, warning
- permanently blocked → <mark><code>blocked — tap to open Settings</code></mark>, danger, and the
  tap opens App details

`shouldShowRequestPermissionRationale` is the authority on "may I ask again", but it is FALSE both
before the first ask and after a permanent denial — so a persisted `contactsAsked` flag separates
those two. Without it a first-ever install would wrongly read as blocked.

**A denial now also produces a Toast**, naming which of the two states you landed in. Silence after
a dialog is the specific thing that made the tap look broken.

Settings opens `ACTION_APPLICATION_DETAILS_SETTINGS`, not the OEM "app permissions" deep link:
Samsung's S22 firmware lands the latter on a page without the contacts toggle.

Set before `render()`, not after — the row's text depends on "have they been asked", so marking it
after the repaint would render the stale state.

Version 0.43.58 / build 212.

---

## 0.43.59 — the row now says what the tap will ACTUALLY do (build 213)
2026-11-02

Third report of *"still fucking misleading"*, and 212 did not fix it because 212 was still
describing the permission state instead of describing the BUTTON.

The defect, stated plainly: a row labelled `tap to grant` is a promise that a dialog will open.
Once Android has decided not to prompt, that promise is false — the tap returns `DENIED` in
roughly zero milliseconds with nothing on screen at all. The user taps, nothing happens, and the
label says the same thing it did before. Nothing in that loop is honest.

**The rule now: the label is a description of what the tap does.**

- never asked → <mark><code>tap to grant</code></mark> → asks
- declined, Android will still prompt → <mark><code>declined — tap to ask again</code></mark> → asks
- asked before and Android will not prompt → <mark><code>tap to open Settings and allow Contacts</code></mark> → opens Settings

That third label cannot be wrong, which is the point. `shouldShowRequestPermissionRationale` alone
cannot pick between the last two — it is false both before the first ask and after a permanent
denial — so the persisted `contactsAsked` flag separates them, and after ANY denial this app
assumes blocked. That is the conservative direction on purpose: opening Settings for someone who
could have been re-prompted is a small cost; promising a dialog that will never appear is the bug
being fixed.

**On denial, Settings now opens by itself** when Android will not prompt again, with a Toast saying
so. Telling someone "allow it in Settings" and leaving them on a screen whose button still says
"tap to grant" is the exact loop being removed.

Version 0.43.59 / build 213.

---

## 0.43.60 — the permission outcome is stated in a modal, not a Toast you can miss (build 214)
2026-11-02

Reported, accurately: *"As a user I wouldn't know this shit didn't work, I want to know that I need
to give the permission in the same fucking modal."*

212 and 213 both improved the ROW. Both were wrong about where the outcome belongs. A Toast fades
over the very row it describes, so the only durable evidence of a denial was a label the user had
to already suspect was there.

**The tap now always opens a modal that states the situation**, and the modal is where the answer
comes back:

- never asked → "STMP needs permission to search your contacts… Android will ask you to confirm on
  the next screen." → **Allow**
- declined, Android will prompt → "Contacts permission was declined earlier." → **Allow**
- blocked → "Android will not show the request again, so it has to be enabled in Settings" →
  **Open Settings**
- **just denied** → the SAME modal returns saying you denied it, and either offers Allow again or
  sends you to Settings

No path silently calls `requestPermissions` and hopes. A permission flow whose outcome the user
cannot see is a permission flow that looks broken — which is exactly how it was reported three
times.

`requestContactsPermission()` now delegates to the dialog rather than requesting directly, so
nothing can bypass the explanation.

Version 0.43.60 / build 214.

---

## 0.43.61 — the 5-second repaint was eating taps (build 215)
2026-11-02

Reported: *"nothing changed no modal"* — after two releases of dialog work that never appeared.

**The device log had the cause, and it was not the dialog.** The tap logged
<mark><code>ViewPostIme pointer 0</code></mark> and **no `pointer 1`**: a press that never became a
release. A gesture cancelled mid-flight.

`uiTick` calls `render()` every 5 seconds, and `render()` begins with
`statusContainer.removeAllViews()` — it destroys and rebuilds every checklist row. A press held
longer than the interval has its View removed from under the finger, so the click never fires.
Pressing a row is a 1–2 second gesture, so this ate them routinely.

**The bug was self-concealing**, which is why it survived three rounds of guessing: nothing
crashed, nothing logged, and the screen looked *identical* afterwards because it had just been
rebuilt with the same content. Two releases of modal copy were written against a tap that could
never reach them. My first two attempts fixed the label and then the dialog — both real
improvements, neither reachable.

**Fix: the tick repaints only when something it displays actually changed.** `renderSignature()`
is a cheap fingerprint of every value the checklist paints — server state, bind address, the three
contacts-permission states, FSI, overlay, battery, vault, AI enabled/ready/download percent, login
email, audit health, and each plugin's title+state. Unchanged signature, no repaint, so rows keep
their identity between ticks and a press can complete. `render()` re-baselines the signature on
every explicit call, so a tap-driven repaint is never immediately undone by the tick.

The download percent is IN the signature on purpose — without it a model download would freeze on
whatever value was on screen when skipping began.

Shortening the interval was the tempting alternative and would have made it strictly worse.

Version 0.43.61 / build 215.

---

## 0.43.62 — APPROVE on the contacts card now asks for the permission (build 216)
2026-11-02

Reported with a screenshot of the approval card — which is the part I got wrong four times:
*"all i want is when clicking approve here I give the fucking permission"*.

Every previous attempt fixed the **home screen checklist row**. The card in that screenshot is
`ApprovalActivity`, an entirely separate surface that I never opened. So the loop was:

1. caller asks for contacts
2. card appears, human taps APPROVE — exactly what the product asks of them
3. the request runs, the plugin finds no `READ_CONTACTS`, and answers
   <mark><code>503 contacts_unavailable</code></mark>
4. the remedy is hidden on a different screen nobody had a reason to visit

**Approving a contacts request is now the moment the permission is requested.** On
`GET /contacts`, APPROVE calls `requestPermissions(READ_CONTACTS)` FIRST and leaves the request
**PENDING** — resolving first would fire a handler guaranteed to fail. On grant it approves for
real; on denial it stays pending with the buttons live.

A denial now says so **on the card**, in an `AlertDialog` naming the two outcomes — ask again, or
open Settings when Android will not re-prompt. Not a Toast: that is the feedback that vanished
before it could be read for four releases.

`needsContactsPermission` is derived from the ENDPOINT on the card, because the plugin cannot help
here — it only runs after approval, so the check has to happen on the gate's side of the line. A
`contactsPromptRunning` guard stops a double tap from stacking two system dialogs, which on some
OEM builds silently drops the first result.

`onRequestPermissionsResult` is now overridden in this Activity too. It logs the grant/deny either
way, because "the route is broken" and "nobody allowed it" look identical from a tailnet.

Version 0.43.62 / build 216.

---

## 0.43.63 — contacts can answer "what is their email", and the card shows the query (build 217)
2026-11-02

Two separate defects, both reported in one message:

1. *"can you get me the email of Ahmed Awadi?"* — the plugin only ever read
   `CommonDataKinds.Phone`, so every row carried a name and a number and nothing else. The address
   book has the emails; I never queried them. That is not a permission problem and no amount of
   granting would have fixed it.
2. *"I can't see more details about the search modal request"* — `ContactsPlugin` never implemented
   `describeRows`, so `rowsFor` fell through to the empty-read default and the card rendered a bare
   endpoint line. The query existed only inside the collapsed JSON blob, so the human was asked to
   approve a search without being told what it would search for.

**Emails.** `CommonDataKinds.Email` is scanned as a second pass, matched on display name OR address,
so a name search finds someone whose address says nothing about their name. Rows from both
providers fold together by `CONTACT_ID` ([ContactRules.collapse]) — one person, every number and
every address, duplicates dropped. `count` still means people.

**Shape change, deliberately breaking:** each contact now carries `numbers[]` and `emails[]` instead
of a single `number`. A contact genuinely has several of each, and picking one would have been a
guess the caller could not see. The audit mirrors it as `numbersRedacted[]` / `emailsRedacted[]`.

**Email redaction** keeps the domain and halves the local part: `ahmed@gmail.com` →
`ahm••@gmail.com`. The domain is shared with thousands of people and identifies nobody; the local
part is the identifying half, so it gets the same half rule as a number. A string with no `@` is
masked whole rather than passed through — returning malformed input raw would leak on exactly the
case nobody tests.

**The card now shows** SEARCH / RETURNS / LIMIT as real rows, so the query is visible without
expanding anything.

Cap accounting also tightened: `Accumulator.add` stops on a new *person* past the cap but keeps
folding extra values for a person already collected, so a truncated reply never drops a number the
person actually has.

404 tests, 0 failures.

---

## 0.43.64 — email-only contacts came back nameless (build 218)
2026-11-02

Found by testing 217 on the device rather than by reading it back: `GET /contacts?query=gmail`
returned 25 contacts, **every one with `"name": ""`**. A list of bare addresses is not an answer —
the human cannot tell who they are looking at.

Cause: `CommonDataKinds.Email.DISPLAY_NAME` is routinely empty for a contact with only an address,
and `Phone.DISPLAY_NAME` is empty for the same contact because it has no phone row at all. So the
name existed on `ContactsContract.Contacts` and nowhere in either scanned provider.

`resolveMissingNames` now runs **after** the scan, batched into ONE query with an `IN (...)`, and
names only the ids that are still blank. At most 25 ids, one round trip, no per-row query.

A failure in that pass is deliberately not fatal: the numbers and addresses are already correct,
and failing the whole search would answer less than the device can.

Also confirmed on device in the same run: `numbers` comes back as an array per person, deduped and
collapsed by contact id — *Ahmed Aly Luxor* carries seven numbers across four countries in one
entry, exactly as intended.

---

## 0.43.65 — the real stamp becomes the launcher icon (build 219)
2026-11-03

Mido supplied the actual artwork: a distressed rubber stamp, STMP inside a bordered frame, on
warm paper. The launcher icon was still the placeholder — a vector disc with four letters drawn
as strokes, which stood in for a logo that did not exist yet.

**Adaptive icon, and `minSdk = 26` is why that is enough.** 26 is the release that introduced
adaptive icons, so `mipmap-anydpi-v26` covers every supported device and no legacy density-bucket
fallbacks are needed at all. Before this the manifest pointed at a single `drawable/`.

**A bitmap foreground, not a traced vector — deliberately.** The house rule is to trace, because
launchers scale the foreground and a raster goes soft. That rule assumes a mark made of a few
dozen shapes. This one is a distressed stamp whose character IS the ink speckle: tracing it means
either thousands of paths or a clean logo that is no longer the brand. So the foreground ships as
a PNG at the five exact density buckets (108/162/216/324/432), each rendered at its own size so
the launcher scales 1:1 and never resamples. The monochrome layer is the same silhouette in flat
white for Android 13 themed icons.

**The frame was kept** (Mido, 2026-11-03: *"A"*). I recommended dropping it — a 2:1 framed mark
inscribed in the circular safe zone spans about 55% of the width, and at 48dp the frame competes
with the launcher's own shape. He chose the framed mark because the frame is the brand, which is
his call to make and the preview let him make it by looking.

The mark fits the safe circle on its DIAGONAL, not its width: a 906x452 rectangle inscribed in a
66dp circle has a half-diagonal of 505px against a 132dp radius, so scaling by width alone would
have clipped the corners. `0.96` of that fit leaves a hairline margin.

**The approval modal was still showing the placeholder disc.** Rendering what actually shipped is
what caught it: the launcher would have been the new stamp while every approval card carried the
old glyph. It now uses `drawable-nodpi/logo_mark.png`, the tight crop with no safe-zone padding
(this is an in-app image, not a launcher icon), sized `WRAP_CONTENT` + `adjustViewBounds` because
forcing a 2:1 mark into a square box letterboxes it into a smear.

`drawable/ic_launcher.xml` stays: it is the pre-26 fallback path and the source of the old glyph.

---

## 0.43.66 — CI: every push to main builds a beta release (build 220)
2026-11-03

A push to `main` now builds, verifies and publishes a BETA GitHub Release tagged
`v<versionName>` with the APK attached. Promotion to stable is a metadata flip, not a rebuild.

**The pipeline is a strict chain, and every step is a hard failure.** Mido, 2026-11-03:
*"no fucking fallbacks of any kind, if I catch you setting a hidden fallback value anywhere in
this pipeline you'll be fired."* So each guard below fails before the next step spends time, and
none of them degrades to "probably fine":

1. decode `STMP_KEYSTORE_B64` → **verify the SHA-1 is the expected key** → fail on mismatch
2. tag guard → fail if `v<versionName>` already exists
3. three Google values → **any empty = fail** before compiling
4. `assembleRelease` → read the APK's OWN badging → packaged version AND signer SHA-1 must match
5. publish, refusing to overwrite an existing release

**Why step 3 exists at all.** `local.properties` is gitignored and `localProp()` returns `""` for
a missing key — non-fatal by design, so the build succeeds and login answers "build not
configured". Gmail, Calendar and Drive would be dead in a release that looked perfectly healthy.

**Why step 4 exists at all.** A filename is not evidence. The last point before an artifact goes
public is the only place that can prove the APK is the one the repo describes.

**The debug-keystore fallback is gone from `build.gradle.kts`.** It silently produced BUILD
SUCCESS with the wrong identity — an APK that will not install over existing STMP, whose SHA-1 is
not the one registered in Google Cloud Console, so OAuth rejects it. Wrong artifact, every signal
green. The replacement guard is scoped by `taskGraph.whenReady` to tasks that actually sign, so
`testDebugUnitTest` and `lint` still run on a machine with no keystore (exactly a CI PR job) while
`assemble*`/`bundle*` fail before compiling. Proven both ways: tests pass with the keystore moved
away, `assembleDebug` fails.

Two bugs in that guard, both found by testing rather than reading:
- a bare `allTasks` inside `whenReady` resolves to `project.allTasks` (every registered task,
  `assemble` included), so it fired on `testDebugUnitTest` — fixed with `graph.allTasks`
- a loose `startsWith("bundle")` matched AGP's internal `bundleDebugClassesToCompileJar`, which
  appears in a plain test graph — fixed by anchoring on a `Debug`/`Release` suffix

**`version-check.py` is rewritten to a tag rule.** The old check compared the working tree against
`HEAD`, which only asks "did you edit this file since the last commit" — and in CI both sides are
identical by definition, so it failed EVERY run. That is not a stricter check, it is a broken one.
The real invariant is that two releases must never share a version, and a release is identified by
its tag, so the tag is what gets checked.

**Beta and stable are the same bytes.** `master` publishes with `prerelease: true`;
`tools/promote.sh` (or the Promote workflow) sends a REST `PATCH` with `prerelease=false`. Not
`gh release edit --prerelease=false` — the CLI's boolean flags have historically ignored an
explicit value, leaving the release a prerelease while exiting 0. A false success is the one
outcome this pipeline must never report, so promotion also re-reads the state afterwards and fails
if it did not take.

**PRs run tests and lint only.** No keystore, no credentials, no build, no release — and notably
no version guard, because a PR legitimately carries the version of the tag it branched from.

Still required before the first run: four repo secrets (`STMP_KEYSTORE_B64`, `GOOGLE_CLIENT_ID`,
`GOOGLE_SERVER_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`). Until they exist the release job fails at step
1, which is the guard doing its job rather than a defect.

---

## 0.43.67 — guard scripts are TypeScript, not Python (build 221)
2026-11-03

Mido, 2026-11-03: *"avoid python prefer ts"*. All repository tooling moved to TypeScript.

**No build step, and that is the reason this is cheap.** Node 22.6+ executes `.ts` files
directly by stripping types, so `node tools/version-check.ts` is a real command — no bundler, no
`tsc` in the run path, nothing to keep in sync between source and output. `tsconfig.json` carries
`"noEmit": true` for the same reason.

**`erasableSyntaxOnly` is the flag that makes this safe.** Node's stripping only handles syntax it
can delete: no `enum`, no parameter properties, no `namespace`. Without that flag a file can
typecheck cleanly and then fail to RUN, which would be the worst kind of guard — one that reports
success by crashing. With it, `tsc` rejects anything Node cannot execute, so a green typecheck
means a runnable script. Verified by writing an `enum` and watching tsc answer `TS1294`.

**Strict everywhere**, matching the app's own rule: `strict`, `noUncheckedIndexedAccess`,
`exactOptionalPropertyTypes`, `noUnusedLocals`, `noUnusedParameters`. `noUncheckedIndexedAccess`
earns its keep immediately — every `regex[1]` is `string | undefined`, so each capture is narrowed
explicitly instead of trusted. Same reason the project bans `as any` in Kotlin.

**One implementation, not two.** `tools/promote.ts` replaces both `promote.sh` and the duplicated
YAML in `promote.yml`. The workflow now calls the script. Two copies of "is this promotable?"
would drift, and the one that ran fewer checks is the one nobody notices.

**CI must pin Node 24.** `ubuntu-latest` ships Node 20, which cannot execute a `.ts` file at all —
the guards would fail with a parse error rather than a useful message. Every workflow that runs a
guard now sets `node-version: '24'` explicitly.

`tools/test-summary.ts` replaces the Python heredoc that read the JUnit XML. It parses with one
regex against the `<testsuite>` attributes rather than pulling in an XML library to read three
integers; a shape mismatch fails loudly instead of guessing.

`package.json` carries no runtime dependencies — only `typescript` and `@types/node` as
devDependencies. `node_modules/` is gitignored.

---

## 0.43.68 (build 222) — 2026-11-03
- Fixed non-ASCII request bodies being corrupted. NanoHTTPD decodes as US-ASCII when no charset is declared, so any UTF-8 byte became U+FFFD. Bodies are now forced to UTF-8 before parsing.
- Fixed the notification card sitting on "summarising…" forever when the summary fetch never returned. The card now ends at its own deadline.

## 0.43.69 (build 223) — 2026-11-03
- Fixed the approval modal hanging on "summarising…" with disabled buttons when the summary fetch died. The loading state now expires and the buttons unlock.
- Changed the risk indicator from a coloured left stripe to a tinted card background.

## 0.43.70 (build 224) — 2026-11-03
- Fixed summary failures reporting a false cause: every failure claimed a 10-second timeout, including ones that finished in 1.4s. Each failure now states its real reason.
- Durations in user-facing messages read as seconds instead of raw milliseconds.

## 0.43.71 (build 225) — 2026-11-03
- Removed the summary filter that discarded the model's description whenever its wording contradicted the request verb, which rejected every answer on some routes.
- Fixed the prompt: a worked example contained the verb "Creating", which the model copied onto read requests.

## 0.43.72 (build 226) — 2026-11-03
- Fixed CI failing before it reached any build step: the Android SDK action installs a `tools` package Google no longer publishes. The SDK platform is now installed explicitly.

## 0.43.73 (build 227) — 2026-11-03
- Fixed eight lint errors, including ed25519 key generation crashing on Android 12 and below: `BigInteger.TWO` requires API 33 and the class initialises at load time.
- Fixed a keystore exception caught by type, which crashed on API 26–27.
- Added the missing API guards for MediaStore Downloads and notification group flags.
- Fixed the approval modal not calling `super.onBackPressed()`.

## 0.43.74 (build 228) — 2026-11-03
- Lint now runs as part of `check` and fails the build on errors. It previously never ran during a normal build, so eight errors shipped unnoticed.
- Raised severity for API-level misuse, missing super calls, and resource leaks.
- Fixed the version guard only checking local tags, which let an already-released version appear unreleased.

## 0.43.75 (build 229) — 2026-11-03
- Release notes are now the changelog entry for that version, instead of a fixed sentence addressed to the maintainer.
- Publishing fails if the changelog has no entry for the version, or an entry with no content.
## 0.43.76 (build 230) — 2026-11-03
- Changelog format fixed: `## <version> (build <n>) — <date>` with one bullet per change.
- Removed names, history and essays from entries; those belong in commit messages.
- Release notes for 0.43.68–0.43.75 rewritten in the new format.

## 0.43.77 (build 231) — 2026-11-03
- Removed the notification posted when an approval modal appears. It only existed to carry the full-screen intent, and the modal replaced it immediately.
- Changed the notification card's risk indicator from a coloured left stripe to a tinted card background, matching the modal.

## 0.43.78 (build 232) — 2026-11-03
- Removed the hardcoded device name from the taildrop script; the target node is now an argument or `STMP_NODE`, and missing it is an error instead of a default.
- Taildrop is now opt-in in `release.sh`, so a local build no longer sends anything to a device.

## 0.43.79 (build 233) — 2026-11-03
- Fixed the ✕ on a notification card being ignored during the misclick guard, and being dead entirely on a card that never got stamped. Dismissing is now unconditional.
- Fixed cards freezing at "summarising…" after being accepted. The stamp that removes them could never finish if its window was added but not laid out, so removal now has a deadline.

## 0.43.80 (build 234) — 2026-11-03
- Changed CI to run tests, lint and the APK build as parallel jobs instead of one chain. Publishing still requires all three to pass.
- Tests now use two forks on CI, where four cores are available; local runs stay at one.

## 0.43.81 (build 235) — 2026-11-03
- Split the app into three screens behind a bottom bar: Home, Plugins and Settings.
- Moved the approval gate settings from Home to Settings, and the plugin list from Home to its own screen.
- Plugins can now be switched off individually. A disabled plugin still answers its routes, refusing with `plugin_disabled` instead of `not_found`.
- Plugins declare their own settings and permissions, so each gets a settings screen without one being hand-built for it.
- TTS settings moved from the global options screen to the Speak plugin that owns them.
- Help, Debug and Logs cannot be disabled: they are how the rest of the bridge is discovered and diagnosed.

## 0.43.82 (build 236) — 2026-11-03
- Removed the Contacts permission row from Home; the Contacts plugin now owns requesting it, with the reason shown at the point of need.

## 0.43.83 (build 237) — 2026-11-03
- Fixed the section header rule striking through its own text; it now sits under the text and spans the full width.
- Made section headers larger.
- Fixed the bottom bar underline spanning a third of the screen instead of the width of the label.

## 0.43.84 (build 238) — 2026-11-03
- Added a Google account plugin holding the signed-in email, token state and sign-in button.
- Moved both notification accesses to the Notifications plugin, which now declares runtime permissions and system access separately.
- Made the Tools list vertical; five buttons across the screen truncated their own labels.
- Removed the Setup header and the Server row from Home; the hero already states both.

## 0.43.85 (build 239) — 2026-11-03
- Added the stamp mark to the Home hero.
- Moved the fingerprint-for-modals toggle from Settings to the gate card on Home, so the mode picker and the proof it requires sit together.

## 0.43.86 (build 240) — 2026-11-03
- Fixed notification cards freezing after being touched. A teardown could re-present the card already on screen, attaching a second window and orphaning the first, which then could not be removed and swallowed every touch.

## 0.43.87 (build 241) — 2026-11-03
- Reduced Home to the status hero, the start/stop control and the approval gate.
- Moved device access, tools and debug to Settings, grouped as Device access, Tools and Debug.
- Moved the vault unlock and the system-settings intents into shared helpers, so no screen holds a second copy.

## 0.43.88 (build 242) — 2026-11-03
- Fixed the "no address" message blaming Tailscale, which is now a recommendation rather than a requirement.

## 0.43.89 (build 243) — 2026-11-03
- Removed the contacts permission flow from Home. Its only entry point was a row that moved to the Contacts plugin, so the whole chain was unreachable.

## 0.43.90 (build 244) — 2026-11-03
- Moved the seven design plans into `docs/plans/`, with an index that says they record decisions rather than describing the code.

## 0.43.91 (build 245) — 2026-11-03
- Fixed the app crashing on open in 0.43.90: the audit banner was still painted by `render()` after the block that created it was removed.
- Added a build-time check that every `lateinit` field is assigned in its own file, so an unassigned view fails the build instead of the phone.
- Removed three dead view declarations left behind by moving the account section.

## 0.43.92 (build 246) — 2026-11-03
- Removed the account email from the Home hero; the Google plugin reports it.

## 0.43.93 (build 247) — 2026-11-03
- Grouped the Plugins screen into Google, Phone, Secrets and Misc sections.
- Added a row per SSH connection under Secrets, each opening its own detail screen.

## 0.43.94 (build 248) — 2026-11-03
- Fixed cards drawing two outlines along different curves, which read as the paper effect printed twice.
- Made section headers larger and gave them a bottom margin.

## 0.43.95 (build 249) — 2026-11-03
- Added a torn top edge and a shadow above the bottom bar.

## 0.43.96 (build 250) — 2026-11-03
- Moved the on-screen debug log out of `MainActivity` into its own file, ahead of the navigation change.

## 0.43.97 (build 251) — 2026-11-03
- Screens no longer call Activity APIs directly; they go through a `ScreenHost` interface, so the host can become a fragment without touching them.

## 0.43.98 (build 252) — 2026-11-03
- The three tabs are now fragments inside one Activity, so the bottom bar is created once and no longer moves when switching tabs.
- App startup, the vault prompt and the once-per-process auto-start now belong to the shell instead of the Home screen.

## 0.43.99 (build 253) — 2026-11-03
- Bottom bar spans the full width, so the tabs are evenly spaced instead of huddled left.
- Bottom bar shadow is real elevation rather than a painted grey band, so it is a genuine transparent blur.
- Removed the thin grey outline around the bar and the empty margin below it, so the paper meets the screen edge.
- Tab labels stay on one line instead of wrapping.

## 0.44.0 (build 254) — 2026-11-03
- New paper toggle: a grained rectangular track with a sliding knob, matching the cards.
- The server, every plugin row, the plugin detail switch and plugin settings all use it.
- The server knob shows a pending look while the service binds instead of sliding on the tap.

## 0.44.1 (build 255) — 2026-11-04
- The toggle is heavier: a routed track with an inner shadow and a cast knob.
- The bottom bar has a real shadow again: its host was clipping the blur away.
- Toggles no longer repeat what the knob already says; only ACTION NEEDED, ERROR and NOT SET UP remain.
- Auto-start on boot uses the toggle instead of an ON/OFF word and a button.
- The server toggle sits inside the status card, where the answer it changes is.
- "use local" is now "on-device", and "Device access" is now "Permissions".
- The AI model row sits with the AI backend instead of under Permissions.
- Callers show on one line, and the menu text is larger with a wider underline that fades between tabs.
- A Google account no longer claims its token is valid for 0 minutes.
- Plugin toggles are right-aligned on every row.
- SSH connections show their key fingerprint, public key and vault entry.
- Secret URLs are each on their own line and tappable.

## 0.44.2 (build 256) — 2026-11-04
- The approval card's countdown now fills the reject button instead of washing the whole card.

## 0.45.0 (build 257) — 2026-11-04
- Settings has an Updates section: it checks GitHub releases, shows what changed, and installs in one tap.
- Stable and beta channels, taken from the promote flag the release pipeline already sets.
- Downloads are verified against the published SHA-256 and refused on a mismatch.
- While the repository is private the check says so plainly instead of showing nothing.

## 0.45.1 (build 258) — 2026-11-04
- The toggle is flat again: the fake depth is gone and both shapes are drawn as jagged, hand-cut rectangles.

## 0.45.2 (build 259) — 2026-11-04
- The toggle uses the app's own torn-paper edge instead of a sharper invented one.
- The server switch sits on its own at the right of the status card, with no label above it.

## 0.45.3 (build 260) — 2026-11-04
- A blocked update install now explains itself in a readable dialog with a button that opens the exact Android setting, instead of a toast that disappears.
- The install permission is checked before the download, not after it.
- Returning from that setting continues the install without tapping again.

## 0.46.0 (build 261) — 2026-11-04
- An available update is good news: green rather than alarm red, and it lives on Home now instead of buried in Settings.
- Fingerprint for modals is a toggle, like every other switch.
- The toggle is about a tenth smaller.
- Text has a 12sp floor and a documented scale, so captions and the modal badge are readable.
- Menu labels are upper case, larger, and bold when selected.
- Settings no longer asks permission to install after downloading 42 MB: it asks first.

## 0.46.1 (build 262) — 2026-11-04
- Tab switching fades now, because the bar is built once instead of being rebuilt on every change.
- The toggle is drawn by the same card drawable as every other surface, so border, wrinkle and grain are identical rather than similar.
- The update check shows a spinner and a last-checked line.

## 0.46.2 (build 263) — 2026-11-04
- Fingerprint for modals can only be flipped after a successful scan, in both directions.
- With no fingerprint available the switch is disabled and off, and says why.

## 0.46.3 (build 264) — 2026-11-04
- The update check responds: check, channel switch and install no longer rebuild the control mid-tap.
- Toggles invert the state they actually show, so settings turn off as well as on.
- The server switch stays usable after stopping, and the copy no longer names a button that does not exist.
- Updates are one card, and "Get started" sits on Home above them.
- The plugin detail toggle is right-aligned, and SSH names its connections instead of counting them.

## 0.46.4 (build 265) — 2026-11-04
- The toggle now wears the same material as the buttons, so every input matches.
- Toggles are 20% smaller, with the same touch target.
- The update card offers a button straight to the releases page, in case checking or installing ever fails.
- A failed install asks for permission before the download, not after it.

## 0.46.5 (build 266) — 2026-11-04
- The Updates card is no longer empty on open: it is filled at build time, and refreshed whenever Home returns to the front.

## 0.46.6 (build 267) — 2026-11-04
- A background update check now appears on Home without leaving and returning to the screen.

## 0.46.7 (build 268) — 2026-11-04
- Every plugin can be switched off, with no exceptions.
- The Google services are one card, and the account email is stated once instead of on four rows.
- The Secrets row opens the secret manager, so each URL finally shows on its own line as a tappable link.
- SSH sits under Secrets with an add action, so secrets are no longer split across two headings.
- Toggle outlines are no longer opaque: the edge alpha is scaled, not overwritten, which is what made it black.

## 0.46.8 (build 269) — 2026-11-04
- A failed update check says what went wrong in a sentence instead of dumping a socket error.
- When a check fails, the releases-page button becomes the main action instead of a quiet link.
- The note under a failed check no longer blames a private repository, which stopped being true when the project opened.

## 0.47.0 (build 270) — 2026-11-04
- The gate modes are renamed to WHITELIST, PARANOID and DON'T DISTURB, in the app, the policy file and the help payload.
- The approval gate has a RULES button that opens the rule editor.
- The server switch sits in the status card's header, top right, so the card is one line shorter.
- Secrets and SSH are separate sections, each with its own add action.
- A paper crease plays on screen switch, and sound effects can now be turned off in Settings.
- The Google plugin can log out.

## 0.47.1 (build 271) — 2026-11-04
- Fixes the server refusing to start after the mode rename: an old policy file is upgraded instead of rejected.
- Anything unreadable now falls back to a safe value, says so in the debug log, and the repaired file is written back.

## 0.47.2 (build 272) — 2026-11-04
- The rules button sits on its own row under the three gate modes, so the modes get the full width.

## 0.47.3 (build 273) — 2026-11-04
- Adding an SSH connection keeps the dialog and everything typed in it when a field is rejected.
- Forms and dialogs no longer draw dark widgets on the cream palette on a dark-mode phone.

## 0.47.4 (build 274) — 2026-11-04
- The approval modal's countdown label is gone; the DENY button itself fills as time runs out.
- The hint card's countdown spans the card again instead of a chip on the right edge.

## 0.47.5 (build 275) — 2026-11-04
- SSH connections list only on the SSH screen, and secrets only on the Secrets screen.
- The duplicates in "Other" are gone: each secret no longer appears as its own plugin row.

## 0.48.0 (build 276) — 2026-11-04
- AI summaries is one switch. Off shows nothing else; on shows two large radio cards for on-device or remote.
- Each card states its own condition, and the download button lives only on the card that is selected.

## 0.48.1 (build 277) — 2026-11-04
- The gate card shows all three modes again; only WHITELIST was visible after the rules button moved.
- Tapping UPDATE shows a loader immediately, until the app restarts or you cancel.

## 0.48.2 (build 278) — 2026-11-04
- The Edit rules button no longer multiplies: it is built once instead of on every repaint.
- The gap under the Fingerprint for modals toggle is normal again.

## 0.48.3 (build 279) — 2026-11-04
- The gate's three modes are large radio cards, the same shape as the AI backend picker, and each says what it does.

## 0.48.6 (build 282) — 2026-11-05
- Radio markers keep the same size and ring weight selected or not; only the fill changes. Selecting one used to make it visibly smaller than the ones it beat.
- Radio markers sit beside the title instead of floating mid-card, and no text can sit behind one.
- The rules button lives inside the whitelist card, where the rule table is actually read, and is greyed out while whitelist is not selected.
- SSH and Secrets cards lead with the friendly connection/secret name, with the address underneath as detail.
- JSON bodies render pretty-printed and coloured wherever they appear, and `\/` is shown as `/` in the rendered copy.
- The SSH approval card colours its command: commands, options, quotes, redirections and `sudo` each read differently, so a long command can be scanned instead of deciphered.
- The command breaks onto a new line at `;`, `&&`, `||` and `|`, at token boundaries only, so a `;` inside quotes stays put.
- Plugin rows reach the modal as rows. They were flattened to label/value pairs, which forced every row in every modal to render the same; the SSH command could not ask to be drawn as code.

## 0.48.7 (build 283) — 2026-11-10
- README documents running privileged commands through a connection pointed at the bridge's own host. The route already fed a modal password to `sudo -S`, so this needed no sudoers change; it was undocumented, and the working shape is not obvious.
- The same section records that chaining through a second host hangs rather than failing, because the intermediate `ssh` consumes the password first.

## 0.48.8 (build 284) — 2026-11-10
- A plugin's switch reads off whenever it cannot serve, so the knob and the ACTION NEEDED label no longer disagree.
- Get started sits directly under the status card, above the approval gate, where a new install meets it before the mode picker.
- "Per-pair rules" is gone from the gate card. The word named the implementation; the screen has been Exceptions since October, and the button now says so too.

## 0.48.9 (build 285) — 2026-11-10
- Every plugin row has a `?` that opens what the plugin does: its routes, the permissions it needs and why, and the system access it wants.
- That dialog is built from the plugin's own declarations, so it cannot drift from what the plugin actually serves.

## 0.48.10 (build 286) — 2026-11-10
- Get started asks which agent you use. Hermes gets its install and config commands with this phone's address already filled in; everything else keeps the generic prompt.
- The Hermes steps name `caller_unverified` as expected rather than a fault, which is where people otherwise conclude the plugin is broken.

## 0.48.11 (build 287) — 2026-11-10
- Get started is a four-step introduction: what the app is, what it can do, how the gate decides, then connecting an agent. It opens by itself on first launch and is reachable from Home afterwards.
- The three gate modes are described in one place shared by the Home card and the introduction, so the two cannot describe different tradeoffs.
- Home's door is labelled WHAT IS STMP? — the old wording promised instructions and delivered one prompt.

## 0.48.12 (build 288) — 2026-11-10
- The new-device prompt no longer stacks: opening it twice showed two screens, and it now has a `not now` action that decides nothing instead of forcing a block.
- The new-device screen explains itself — what just happened, and that this is asked once per computer rather than once per request.
- A plugin's `?` speaks plain English now: what the feature is for, and example things to ask it. The technical sheet it used to show read like API documentation.
- Dialogs are drawn on the app's own paper instead of a system dialog.

## 0.48.13 (build 289) — 2026-11-10
- Settings repaints when you come back to it, so a permission granted in Android Settings shows as granted instead of still offering to grant it.

## 0.48.14 (build 290) — 2026-11-10
- The introduction is shorter and larger: each step is a headline plus a few lines, and body text is bigger.
- The introduction now helps you grant the four system accesses it depends on — notification access, draw over apps, lock-screen popup and battery exemption — each row showing live state and opening the right screen.
- The updates card always states the installed version, instead of only when a check came back up to date.
- A check in progress no longer stays "checking…" forever, which previously also disabled CHECK NOW.
- Speaking during a call is refused with `tts_in_call` instead of reporting success for a sentence nobody heard.

## 0.48.15 (build 291) — 2026-11-10
- A plugin's help is a 💡 badge on the left of the row instead of a `?` beside the switch. A question mark beside a control reads as a fault on that control; on the left it marks the thing being explained.
- The badge sits in a 44dp touch frame with a drawn disc inside it, so it is easy to hit without being a large target on screen.

## 0.48.16 (build 292) — 2026-11-10
- The bridge starts on Android 12 and 13 again. Below API 34 the service now posts its foreground notification with no type, because asking for `dataSync` against a manifest that declares `specialUse` fails the type-subset check and throws out of `startForeground`.
- No behaviour change on Android 14+, which still starts as `specialUse` and keeps the no-6h-cap property.

## 0.48.17 (build 293) — 2026-11-11
- The secret vault can be created on phones without StrongBox. The TEE fallback reused the StrongBox attempt's key spec, still asked for StrongBox, and failed with "could not create the vault key", which left Secrets and SSH locked.
- A vault key that cannot be created now reports the real Keystore exception of each attempt instead of a generic message.

## 0.48.18 (build 294) — 2026-11-11
- A sudo request no longer shows ALWAYS NOTIFY / ALWAYS ASK. That button approved without the password: one tap ran the command with an empty sudo password, skipped the password path, and wrote a rule relaxing every SSH command.
- The gate now builds no exception offer for a request that needs a password, and the modal drops one if it arrives anyway.
- The gate refuses any approval of a password request that does not carry the password, whichever button sent it. DENY still works.

## 0.48.19 (build 295) — 2026-11-11
- Every screen and state is captured as a reference screenshot in light and dark at normal and large text, and CI fails when one changes unintentionally.
- Unit tests run with a fixed English locale, so number formatting no longer depends on the machine.

## 0.49.0 (build 296) — 2026-11-11
- Dark mode follows the system on every screen, dialog, the approval modal and the floating notify card, all drawn from one palette.
- Text is set in Geist and Geist Mono, bundled with the app; the handwritten face, paper grain and torn edges are gone.
- The primary colour is ink; the old stamp red is removed, and error states use amber instead of red.

## 0.49.1 (build 297) — 2026-11-11
- Every dialog uses the app's own look in light and dark, including the ones that were stock white system dialogs.
- The new controls for the redesign (rocker switch, mode knob, hold key, readout, LEDs, punch) are built and tested; screens adopt them in the next releases.
- The hold key can no longer approve after it is disabled or disarmed mid-hold; it returns to idle and ignores keyboard holds while disabled.

## 0.49.2 (build 298) — 2026-11-12
- The approval screen leads with the exact command or payload on a dark readout, with risky shell tokens such as sudo, rm -rf and | sh underlined; the AI summary sits below it, marked as possibly wrong.
- Requests that run or delete something, and sudo commands, are approved by holding the key for 1.2 seconds; deny is always one tap.
- After you decide, a punch and a short receipt confirm what happened, and pressing back shows the DENIED punch and receipt like a DENY tap.
- When approving needs the fingerprint, a contacts request asks for the fingerprint first; granting the contacts permission can no longer approve without it.
- A hold can no longer approve after another app covers the screen or the screen loses focus mid-hold; the key returns to idle.

## 0.49.3 (build 299) — 2026-11-12
- The floating Don't-disturb card uses the new look in light and dark: a clear verb chip, a larger ✕ target and a thin countdown bar; tapping, the ✕ and silence still behave exactly as before.
- Decisions on the card show the new punch, and neither the card nor the new-device screen accepts taps while another app is drawn over them.

## 0.49.4 (build 300) — 2026-11-12
- The Don't-disturb card now refuses an approve tap if another app covered it at any point during the tap, not only when the finger lifted. A cover pulled away just before release could otherwise steer an approval.

## 0.49.5 (build 301) — 2026-11-12
- Small keys keep their side padding, so their labels no longer touch the outline.
- An approval that arrives after a request expired no longer shows APPROVED.

## 0.49.6 (build 302) — 2026-11-12
- Two-line labels on small keys stay inside the key face instead of touching the outline.

## 0.49.7 (build 303) — 2026-11-12
- Settings is redrawn in the new look in light and dark, with the page and every label named Settings.
- Settings → Permissions now lists Notification access, the permission behind "needs attention", and says what each permission affects.
- The AI section shows what is wrong in words for every model state, and the re-download dialog quotes the real model size.
- Fingerprint for approvals is now on by default for new installs, and it can be changed in Settings → Approvals as well as on Home. A phone without a fingerprint keeps it off.
- The debug log is a mono readout with a copy button, and forgetting a caller asks first and sits clear of the caller's name.
- In the remote model server form, Save and Refresh models now use the address and token you typed, not the ones from before you started typing.
- Switching the AI knob away from "On this phone" while the model downloads no longer repaints the page four times a second, so the remote server form keeps its focus and keyboard.

## 0.49.8 (build 304) — 2026-11-12
- Home is rebuilt as a control panel: the bridge switch with its address on a dark display, the gate mode as a three-position knob, and the latest requests listed underneath.
- When the bridge says "needs attention" it now names the reason and links straight to the setting that fixes it.
- The bottom tab bar is taller, has icons, and no longer shrinks its labels at large text sizes; switching tabs slides and plays the tab sound again.
- Update problems appear in a dialog you can read instead of a toast that vanishes.

## 0.49.9 (build 305) — 2026-11-12
- The request log, request detail and Exceptions screens are redrawn with a back arrow, wrapping filter chips and no emoji; a gate change no longer looks like an error.
- Request detail shows times in your time zone with the zone named, and no longer prints "null", raw millisecond counts or a trailing dash.
- Exceptions names plugins and modes the way the rest of the app does (Whitelist, Paranoid, Don't disturb), keeps Remove clear of the mode button, and its add-exception dialogs match the app.

## 0.49.10 (build 306) — 2026-11-12
- Exceptions keeps its top bar when you tap Remove, Set, Save or Try again, so the back arrow stays clear of the status bar and the list keeps its gesture-bar padding.

## 0.49.11 (build 307) — 2026-11-12
- Plugins that need action now sort to the top of their group, and Debug, Help, Logs and Probe are tucked into a collapsed Developer group.
- The plugin help dialog now tells you the real state of the plugin; it used to say "On, and ready" even when the plugin was not set up or needed a permission.
- Plugin rows no longer break at large text sizes, the help bulb is a full-size tap target, and the technical route and error text under each plugin name is replaced with plain words.
- In a build without Google sign-in, the Google, Gmail, Calendar and Drive rows say so instead of pretending to be set up.

## 0.49.12 (build 308) — 2026-11-12
- The plugin screen has a back arrow and a clear layout: switch, permissions, system access, settings and routes each sit in their own panel, with plain GRANTED, OPTIONAL and NEEDED labels.
- System access rows now say why each one is needed, and editing a text setting shows an error in the dialog instead of silently not saving.
- A plugin that is not registered now shows a message instead of closing the screen, and the Google sign-in screen has the same back arrow and readable error messages.

## 0.49.13 (build 309) — 2026-11-12
- The Secrets and SSH screens are redrawn with a back key, and a vault card at the top that says whether the vault is locked, not created or needs a reset, with a key that fixes it and the real reason when unlocking fails.
- Secret and SSH cards, every dialog and the new-device screen use the new look; errors show on the screen instead of in a toast that disappears.

## 0.49.14 (build 310) — 2026-11-12
- The introduction is redrawn: the permission rows show their live state with a light and a word, the footer no longer covers the last line at large text, and the commands and the agent prompt are in a readout you can select and copy.
- The introduction's wording is fixed: it points to the bulb on the Plugins tab and to Settings for permissions, and the agent prompt is no longer broken into short lines.

## 0.49.15 (build 311) — 2026-11-12
- The running notification now shows the address, port and gate mode, and has Open and Stop bridge buttons; the lock screen shows only that STMP is running.
- The problem and new-device notifications say STMP, use the new icon and tell you the next step.
- New launcher icon with a themed (monochrome) version, a new notification icon, and a new in-app logo; notifications, prompts and copied labels now say STMP.

## 0.49.16 (build 312) — 2026-11-12
- The last of the paper look is removed: the handwritten font, the stamp images and the old logo bitmap no longer ship, which also makes the app smaller.
- Every control is at least 48dp with room between neighbours, every heading and switch is named for screen readers, and all text meets its contrast minimum in light and dark.
- The new-device screen opens with the name field focused, the Exceptions screen applies each change to the policy as stored now instead of an older copy, and the request log filters keep screen reader focus when you tap one.
- Google sign-in keeps the "Already signed in" note on screen while the account picker opens, and the fingerprint-unavailable messages use the name of the setting, "Fingerprint for approvals".

## 0.49.17 (build 313) — 2026-11-12
- Stopping the bridge no longer leaves a "starting" notice behind, even when a network check was still running as it stopped.

## 0.49.18 (build 314) — 2026-11-12
- On the Gmail, Calendar and Drive screens, tap the "Sign in" status to sign in with Google.

## 0.49.0 (build 293) — 2026-11-11
- The Refined Paper foundation bundles Atkinson Hyperlegible Next for readable UI text and JetBrains Mono NL for exact payloads, while Handlee remains the display and stamp face.
- Semantic text and control colours meet the agreed contrast floor; the stamp accent and destructive danger are now distinct.
- Paper switches expose their role, checked state, pending state and keyboard activation to accessibility services without moving before a real state change.
- Home now separates live health and specific remedies from pending requests and recent decisions; Activity keeps queue snapshots and recorded request details read-only.
- Home, Activity and Preferences replace the artificial Plugins/Settings split. Plugin-owned settings and the verified update/install flow keep one implementation in the consolidated Preferences screen.
- Approval facts come before optional model context, long payloads have visible overflow guidance, and the paper entrance, stamp impact and denial tear have reduced-motion and forced-exit paths.

## 0.49.1 (build 294) — 2026-11-11
- Preference taps no longer freeze: rebuilding the screen now waits until the tap finishes instead of tearing down the view mid-click, and bursts of updates coalesce into one rebuild.
- The Activity filter chips now carry real gaps and margins instead of rendering as one touching bar; the same spacing reaches the older log screen.

## 0.49.2 (build 295) — 2026-11-11
- A crash now records its own stack trace to a file, and `GET /debug` reports it as `lastCrash`. Previously the trace rotated out of logcat before it could be read, so a crash report had no evidence to act on.
- The plugin inventory no longer re-orders its own children while Android is still attaching them, which could tear the hierarchy apart mid-dispatch.

## 0.49.3 (build 296) — 2026-11-11
- A frozen screen is now detected and its stack recorded, reported as `lastStall`. A hang produces no exception, so the crash handler added in 0.49.2 could never have seen it.
- Home shows the last crash or freeze and copies the full trace on tap, so the evidence is readable when the bridge itself is what failed.
- One plugin whose stored state is corrupt can no longer take Preferences down with it; its row names the failure instead.

## 0.49.4 (build 297) — 2026-11-11
- Preferences opens again: the plugin inventory rendered itself before its own group list was initialised, so every construction threw a NullPointerException. The exception surfaced as the tab freezing, then as a crash.
- A construction test now asserts the declaration order the initialiser depends on, and renders the inventory for real, so this cannot ship again.

## 0.50.0 (build 298) — 2026-11-11
- Every animation plays, always. The reduced-motion branch collapsed the approval stamp to a 110ms static fade with no contact frame, so the receipt for a security decision was invisible; it is gone, along with the whole `reducedMotion` path.
- A request that cannot be shown an approval prompt is now REFUSED immediately as `prompt_unreachable`, naming the grant to give. Previously it waited the full 60 seconds and answered `approval_timeout`, which is indistinguishable from a human declining.
- Home states it outright when neither full-screen intent nor draw-over-apps is available, because that combination makes the entire gate unreachable.

## 0.50.1 (build 299) — 2026-11-11
- The approval stamp is visible again after a fingerprint. Its clock started while the biometric window was still closing, so the whole animation elapsed behind that dialog and finished on its first visible frame; it now waits for window focus, with a bounded fallback so a platform that never reports focus plays a late stamp instead of none.
- The exit backstop includes that wait, so it can no longer dismiss the modal mid-wait and cancel the stamp it protects.

## 0.50.2 (build 300) — 2026-11-11
- The help badge is half its previous size and sits flush against the row title, so it no longer reserves dead space beside the name it belongs to.

## 0.50.3 (build 301) — 2026-11-11
- The approval stamp now HOLDS its result for over a second after the motion finishes. The animation was running correctly at 650ms, but that is shorter than the beat it takes to look back at the page after dismissing a fingerprint dialog, so the receipt was real and never seen.
- A drawn-frame count is logged with the stamp's completion, so "the animation did not play" is now distinguishable from "it played too briefly" without guessing.

## 0.51.0 (build 302) — 2026-11-11
- The approval stamp no longer expires. It plays, settles at full ink, and stays until the human taps it, so no clock can remove the receipt before it has been read.
- The frame counter that claimed "105 frames" was incrementing in the tick, not in `onDraw`, so it measured the timer rather than the paint. It now counts real draws and logs the first one, which is what makes an invisible stamp diagnosable.
- Pressing back after a decision resolves the request and leaves the stamp on screen instead of destroying the window in the same frame.

## 0.51.1 (build 303) — 2026-11-11
- The stamp is visible. It was painted on every frame and then covered: `PaperEntranceView` carries 4dp of elevation, and a ViewGroup draws children in ascending Z order once any child has elevation, so the sheet was painted over an overlay sitting at the default Z=0. The overlay and the denial tear now carry 32dp, above the sheet's 28dp entrance peak.
- The same cause made the modal appear frozen: the sheet on top received the taps the stamp was waiting for. Both are fixed by the one elevation change.

## 0.51.2 (build 304) — 2026-11-11
- The stamp no longer leaves a doubled shadow: the ink bleed was held at full strength for as long as the stamp stayed on screen, and it draws the mark plus two offset copies. It is a 240ms transient at impact again.
- The modal dismisses itself after the stamp is read; the previous "wait for a tap" turned every approval into a screen that never closed. A tap still dismisses early.

## 0.51.3 (build 305) — 2026-11-12
- Getting started installs the Hermes plugin by its catalog name (`hermes plugins install stmp --yes-deps`) instead of the GitHub path, now that `stmp` is a curated entry. The bare name resolves to a reviewed, SHA-pinned commit and can be moved forward by `hermes plugins update`.

## 0.51.4 (build 306) — 2026-11-12
- A locked vault now asks for a fingerprint instead of refusing the request. Routes that read a stored credential (`needsVault`) unlock before their approval card is built, so the request survives instead of dying in milliseconds with `secret_locked` and no prompt.
- Unlocking is not approving: the fingerprint only releases the key, and the real host and command are then shown for a separate APPROVE.
- Dead-end vault states (never created, unrecoverable, orphaned) and gate modes with no fingerprint surface refuse with the specific remedy instead of showing a prompt that cannot succeed.

## 0.51.5 (build 307) — 2026-11-12
- Added an opt-in `-PemuAbi=true` build flag that adds `x86_64` alongside `arm64-v8a`, so an x86_64 emulator can load the native model library. A normal build is unchanged and still arm64 only.

## 0.51.6 (build 308) — 2026-11-12
- The stamp plays on a hint card again. Its overlay window cannot take focus, so the arming loop never armed and the removal backstop fired first: zero frames, and no impact callback, which is where the sound came from.
- The stamp lands on the card it approves, sized from the card's own rect rather than the whole display.

## 0.51.7 (build 309) — 2026-11-13
- The card stamp sits half its own height higher, so its lower edge meets the card's centre and the summary stays readable. The offset uses the resting height, keeping the slam one downward press rather than a wobble.

## 0.52.0 (build 315) — 2026-11-13
- This build includes everything from upstream 0.49.0 through 0.51.7, brought into the new look.
- STMP now asks for your fingerprint when a request needs a locked vault, then carries on with the request.
- Crashes are recorded, and a frozen main thread is detected; the last crash shows on Home.
- Plugin groups no longer fail to draw on first open, and preference taps no longer freeze.
- A request that cannot be shown on screen is refused instead of waiting.
- Setup docs install the Hermes plugin by its catalog name.
- Added an opt-in emulator ABI build flag.
- The hint card stamp fixes from upstream 0.51.6 and 0.51.7 are not needed: the new punch already plays on the card.

## 0.52.1 (build 316) — 2026-11-13
- A status row with a long value, such as "Not connected", now puts that value under its title so the text keeps its width at large font sizes; the status light sits on the first line and the action key is at the end of the row.
- The gate selector labels have room on each side and no longer run together.
- In dark mode the switch OFF cap and label, and the outlines of the small keys and read chips, are clearly visible.
- The download key keeps its label readable over the progress fill, and disabled keys and fields now look disabled.
- Switches and rows show their pressed and focus states, and the focus ring is drawn clear of the control and inside its own edges, so all four sides show.
- The recent-request rows line their text up in one column whatever the verb.
- Deleting a caller, secret, SSH connection or the vault asks with a Keep key first and an outlined delete key set apart below it; the Exceptions mode picker has large rows with a radio mark and shows the current mode.

## 0.52.2 (build 317) — 2026-11-13
- Crash recording and frozen main thread detection are now really switched on at start-up, so the last crash and stall show up on Home and in the debug commands.
- Setup docs now really install the Hermes plugin by its catalog name.
- Removed the unused paper-look fonts from the app again.

## 0.52.3 (build 318) — 2026-11-13
- Updates: an update is offered only when its build number is higher than the installed one, and never when it would be a downgrade (a release with no build number falls back to comparing the version name). Release notes show as plain bullets without the heading line or Markdown marks.
- Plugins: a plugin that is not in this build (the Google ones without sign-in) shows its switch OFF and locked, is not counted as on, offers no Sign in key and says "This build has no Google sign-in. Install the official release to use it." everywhere, including the Google sign-in screen, which now has a Back key.
- Plugin screen: a plugin that is switched on but cannot serve reads "Can't serve" instead of "Disabled", never says "Open this plugin" from inside the plugin, the SSH plugin has an Add connection key, routes list one per entry with the description on its own indented line in sentence case, and the Google account email only wraps before the "@".
- Request log: Home and the log show the same summary text. Turning "Fingerprint for approvals" on or off reads "Fingerprint for approvals: on" or "off", a settings save that kept the gate mode no longer reads "WHITELIST to WHITELIST", a gate change has no verb chip, "Denied, phone unattended" is grey and counts under Denied, and a gate change or an unknown path has no Decision panel. "Rule" reads "Exception" and "Switch was" reads "Gate mode before".
- Errors: one error card everywhere. Raw error text sits behind a "Details" key inside the card, with a way to try again (policy, exceptions, callers, vault lists, model download, request log) or to delete a damaged SSH connection. Messages after a failed secret or SSH action can be dismissed.
- Home: the bridge card says "Starting..." or "Stopping..." while the switch settles instead of "Stopped", "Edit exceptions" only shows in Whitelist mode where exceptions apply, the log warning no longer sits against the bridge card, and the Updates key no longer repeats "Checking".
- Copy: "Needs action" is the one word for something that needs you, onboarding step 4 no longer says STMP can only ask more, permission states read Granted, Needed or Optional on every screen, the Secret vault sits outside Permissions, a wrong model file with equal rounded sizes shows exact bytes, empty secret and SSH lists say to unlock the vault first when it is locked, and "GitHub" keeps its capitals.
- Onboarding: step 1 is left out once the bridge is on, and Copy prompt and Copy commands are off while the address is still a placeholder.

## 0.52.4 (build 319) — 2026-11-13
- Settings: the Notification access row no longer shows "Not connected" when nothing is granted; it reads "Needed" like a fresh install.
- SSH: a damaged connection offers an outlined Delete key, like every other delete key, instead of a filled one.

## 0.52.5 (build 320) — 2026-11-13
- This build includes upstream 0.51.8 and 0.51.9, kept in the new look.
- The secret vault can be created on phones without StrongBox. A vault key that cannot be created now reports the real Keystore error of each attempt.
- A sudo request can no longer be approved by the exception button or by any approval that does not carry the password. DENY still works.

## 0.52.6 (build 321) — 2026-11-13
- Home: while the bridge switch settles, the card now says "Starting..." after you turn it on and "Stopping..." after you turn it off. It had the two the wrong way round.

## 0.52.7 (build 322) — 2026-11-13
- Home: "Starting..." and "Stopping..." now actually show after you flip the bridge switch, and stay until the bridge reaches the state you asked for.

## 0.52.8 (build 323) — 2026-11-13
- Home: if the bridge fails to start or stop, the card no longer stays on "Starting..." or "Stopping...". It shows the failure as soon as it happens, or when the wait runs out.

## 0.52.9 (build 324) — 2026-11-13
- The approval card shows the whole command. A long command wraps only at spaces, every wrapped line ends in a visible arrow so a wrap is never read as a space, and a command taller than its box says how many lines are still hidden.
- Dangerous words such as "rm -rf" and "| sh" are never split across two lines, and the underline stops at the word instead of running under the space after it. IPv6 addresses wrap after a colon, so a block of digits is never cut in half.
- The Don't disturb card spans the screen, names who is asking and which plugin, shows the whole action without cutting it short, and spells out the risk (HIGH, MEDIUM, LOW). A HIGH risk card is outlined and its label is inverted, so it cannot be mistaken for MEDIUM.
- The key that approves and saves an exception now says so: "Approve + add exception" (or "Also add exception" where you hold to approve).
- While another app covers the approval screen or the new-device screen, the keys look disabled instead of only ignoring your touch.
- The approval screen keeps room for the request when the fingerprint notice, the cover warning and the password field are all showing: the notices share one panel and the lower block scrolls on its own. A line above the keys and a fade show that the content scrolls.
- The "To approve" caption under the hold key stays on one line, the countdown and the request number no longer run together, and the AI summary shows its risk on its own line.
- The approved and denied stamp grows with its text and shrinks to fit the screen, and the receipt slip is as tall as its lines instead of a fixed height, and sits clear of the keys.
- The new-device screen explains a cancelled fingerprint check in its own words and in amber instead of showing the system's wording.
- The Don't disturb card now really names who is asking: the caller's name from your registry appears next to the plugin, and "unknown requester" is shown if a name is ever missing, instead of leaving it out.
- Long commands and addresses wrap by their real drawn width, so Arabic, Chinese, emoji and tab characters can no longer make the phone wrap a line silently. Every wrapped line ends in the arrow, even on a very narrow screen or at the largest text size, and an IPv6 address is only cut with the arrow.
- A wrapped command that contains Arabic or other right-to-left text keeps its left-to-right order on every line, and the wrap arrow always sits clear of the text at the line end.
- The Don't disturb card shows at most three lines of the action and then says how many more lines there are, so a very long email subject can no longer push the risk word off the screen.
- The Don't disturb card counts the hidden lines of a long action the same way it draws them, so "+N more lines" can no longer be missing when the fourth line is cut off.
- On an approval card, a line that is cut in the middle of a word or address now ends in a different arrow than a line that wraps at a space, so a wrap can no longer be read as a space or as part of the word before it.

## 0.52.10 (build 325) — 2026-11-13
- Rows that open something (the Debug log row, the toggles) leave a small margin on each side, so the keyboard focus ring no longer cuts through the title or the switch.
- The status light on a one-line row sits on the title line instead of floating above it.
- A freeze report on Home now has a Dismiss row, and it is cleared by an upgrade like a crash report. When both a crash and a freeze are recorded, Home shows the crash and copies the crash.

## 0.52.11 (build 326) — 2026-11-13
- Field hints are always in the regular face and end in an ellipsis instead of being cut mid-letter; the longest hints in Secrets and Settings are shorter.
- "Not in this build" on the Plugins list breaks as "Not in / this build" instead of leaving "build" alone on a line.
- The version in the Home header reads "v0.52.11", not "V0.52.11".

## 0.52.12 (build 327) — 2026-11-13
- The approval card, the Don't disturb card and the request log now show every hidden character in a request as a visible `\u{XXXX}` mark in the caution colour (text-direction controls, zero-width characters, soft hyphens, control codes), so the text you approve can no longer be re-ordered or hide something.
- A `↵` or `↩` that is part of a command is shown as its own mark, so it cannot be mistaken for a line break the app added.
- Selecting text in a command box offers only Copy and Select all, and a long press no longer drags the selection out, so a wrapped command cannot leave the app with the added line breaks in it.
- A command that cannot fit the screen is marked with a caution-coloured ellipsis and read out as "Text did not fit" instead of being cut off silently.
- A Don't disturb card that cuts off part of the request ("+N more lines") no longer approves when its time runs out. It says so on the card, and only a tap approves it.

## 0.52.13 (build 328) — 2026-11-13
- The fingerprint prompt that approves a request now shows the request's method and path with hidden characters written out as `\u{XXXX}` marks, like the approval card. Before, it showed them raw, so a path could be re-ordered on the one screen that asks for your fingerprint.
- More invisible characters are now shown as marks: the combining grapheme joiner, Hangul and Khmer fillers, variation selectors (U+FE00 to U+FE0F and the tag-block ones), U+2060 to U+206F, and the whole tag block. This is strict on purpose: U+FE0F and the zero-width joiner are marked even inside an emoji, so a heart or a family emoji in a request may show a small mark next to it.
- The exact list of what is marked is now one shared file in the repository (`contract/text-sanitiser.json`) with test cases, so the iOS app can show the same thing.

## 0.52.14 (build 329) — 2026-11-14
- A field hint that is too long for its box now ends in an ellipsis; it is cut to fit the box at every text size.
- A crash or freeze report on Home no longer touches the Bridge card below it, and a freeze report names the code that was running in short form.
- The request detail screen keeps the status light and the clock on the first line of a long title, and the verb tag sits beside the plugin name.
- In a secret's card the Delete key is the last thing in the card.
- Every dialog is the same width.
- The debug log lines up wrapped lines under the message, not under the time, never shows a half-cut line at the top, and its Show/Hide and Copy keys are the same kind of key.
- Home cards all open with their own heading, and the secondary keys (What is STMP, Check now, Open releases page) are the same small key.
- A caller in Settings has a status light: green when allowed, hollow when blocked.

## 0.52.15 (build 330) — 2026-11-14
- The list of hidden characters that get a visible mark is now written out in full for one Unicode version (15.1) instead of being looked up in each phone's own tables. Before, a character such as U+0890 or U+13430 could be marked on one device and shown raw on another. The iOS app reads the same list.
- The spoken announcement of an AI summary, the Home list of recent requests (shown and read out), the "text did not fit" reading and the plugin name in the request log now show hidden characters as marks too, so TalkBack and the screen cannot be fooled by a summary or request text.
- The line under the fingerprint prompt stays on one line (a newline or tab shows as `\u{000A}` or `\u{0009}`) and stops at 120 characters with a "(+N chars)" ending, so a long or multi-line path cannot push the real request out of view.

## 0.52.16 (build 331) — 2026-11-14
- What a screen reader says for a request-log row now shows hidden characters in the plugin name as `\u{202E}`-style marks, the same as the text drawn on screen; the plugin line on the row also draws them marked. The same applies to the plugin name on an exception row and to a caller's name on the Forget key.
- In Settings, the debug log's Show log / Hide log key keeps its size and place when the log opens; the Copy log key gets its own half of the row instead of squeezing the first.
- Field hints that were too long for a narrow field at large text sizes are shorter, so none is cut off any more, and a screen reader or autofill still gets the full hint where one is cut.

## 0.52.17 (build 332) — 2026-11-14
- Plugin names, caller addresses and rule names taken from the request log, the exceptions or the callers now show hidden characters as `\u{202E}`-style marks everywhere they are drawn or read out, including the heading of a request's detail page, instead of only on the screens checked one by one.
- A field's hint is kept whole and cut only when it is drawn, so a screen reader announces the full hint when the field is empty and when it gets focus. The 0.52.16 note that autofill gets the full hint was wrong: autofill is turned off in these fields.

## 0.52.18 (build 333) — 2026-11-14
- A plugin name with hidden characters in the request log, the request detail headline and its Plugin row draws the `\u{202E}`-style marks in the caution colour again, like every other stored text.

## 0.52.19 (build 334) — 2026-11-14
- The request log no longer stores a credential. Any header or query parameter whose name contains token, key, secret, auth, session, signature, cookie, password, jwt, bearer, credential, passwd, pwd or sig keeps its name and shows `[redacted]` as its value, and the Authorization header is now kept as `[redacted]` instead of being left out.
- The match is strict on purpose: a harmless name that merely contains one of those words (monkey, keyboard, author) is redacted too, because a leaked credential cannot be taken back from the log.

## 0.52.20 (build 335) — 2026-11-14 — CURRENT
- A fingerprint sensor that is locked out or faulting for a moment no longer turns "Fingerprint for approvals" off; opening Home or Settings leaves the switch as you set it.
- While the sensor is unavailable, an approval that needs your fingerprint asks for your phone PIN instead, and the card says so. With no screen lock set, it cannot be approved and says why.
- Turning "Fingerprint for approvals" off while the sensor is unavailable asks for your phone PIN; turning it on still needs a working fingerprint.
- Only a phone with no fingerprint sensor, or none enrolled, switches the setting off by itself, as before.

## Keeping this file honest

- **Format, always:**

  ```
  ## 0.43.70 (build 224) — 2026-11-03
  - Fixed summary failures reporting a false cause.
  - Durations in user-facing messages read as seconds.
  ```

  Heading: `## <version> (build <n>) — <date>`. Body: bullet lines. Nothing else.

- **No names.** Not mine, not anyone's. Release notes describe the software.
- **No history.** Not what was tried, not what failed, not what the old behaviour used to be,
  not how many releases it took. If a reader needs the archaeology, it is in the commit message.
- **No essays.** One bullet per change, one or two sentences. Say what changed and, briefly, why
  it mattered. If a bullet runs past two sentences, it is a paragraph pretending to be a bullet.
- **Newest entry at the BOTTOM**, above this section.
- **Every commit updates this file** — a version bump without an entry is an incomplete change.
- **`— CURRENT` on the newest entry only.** `tools/changelog-check.ts` enforces that, plus the
  version/build match with `app/build.gradle.kts`.
- **No commit hash in an entry.** A hash is computed from its content, so it cannot contain
  itself. Find the commit later with `git log --oneline --grep "<version>"`.
- **Do not rewrite old entries** when a release is corrected; add a new one.
