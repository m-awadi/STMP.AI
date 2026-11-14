# STMP for iOS

A native iPhone client of STMP: a foreground-only HTTP bridge that agents call, with plugins, an approval gate, a secrets
vault, SSH and an audit log, in the Instrument design. The Android app in the repository root is the source of truth for the
HTTP contract. The iPhone serves only while STMP is open (Q1), on the tailnet only (Q8).

Everything iOS lives in this folder. Nothing here is built by the Android Gradle project.

## Layout

| Path | What |
|---|---|
| `project.yml` | xcodegen spec. The generated `STMP.xcodeproj` is gitignored and never committed. |
| `STMPKit/` | One Swift package with all non-UI logic. `swift test` runs it on the host. |
| `App/Sources/{App,Composition,Platform,DesignSystem,Features}` | The app target (one module, MainActor by default). |
| `App/Tests/` | Hosted unit and snapshot tests (`STMPTests`). |
| `App/UITests/` | UI tests (`STMPUITests`, T17). |
| `contract/` | JSON fixtures shared with Android (see `contract/README.md`). |
| `scripts/` | `dod.sh` and its checks. |
| `changes/` | One changelog fragment per ticket. |

## The definition of done

```
ios/scripts/dod.sh --ticket T<n>        # the ticket DoD; must end with `DOD PASS T<n> <sha>`
ios/scripts/dod.sh --ticket T<n> --no-sim   # inner loop only: stops after the package tests with `DOD PARTIAL`
ios/scripts/dod.sh --ticket T<n> --checks-only   # hygiene, ownership, changelog; `DOD CHECKS PASS`
ios/scripts/dod.sh --ticket T<n> --record   # records snapshot references; ends `DOD RECORDED`, which is not a DoD result
ios/scripts/dod.sh --merge              # the merge DoD on a candidate (adds the Release device build)
ios/scripts/dod.sh --self-test          # attribution lists, ownership no-overlap, A and P rows, the step watchdog
ios/scripts/dod.sh --cleanup            # deletes this worktree's simulator and ios/.derived
```

The steps, in order: context (Swift 6, disk floor, runtime, Android baseline), hygiene greps, ownership, changelog, swift-format
lint, `swift test` on the host (a host slot), xcodegen, a simulator slot, the simulator, `build-for-testing` and
`test-without-building`. `--merge` also builds Release for `generic/platform=iOS` and fails if `strings` finds a test launch
option or `nm` finds an `STMPTestSupport` symbol.

Required environment: `STMP_BASELINE_SHA` (the Android SHA the fixtures were read at) and `STMP_ANDROID_REPO` (default
`/Users/mohamed/Developer/STMP.AI-integration`, read with `git show` only). Optional: `STMP_MIN_FREE_GB` (12),
`STMP_HOST_SLOTS` (2), `STMP_DOD_SLOTS` (2), `STMP_STEP_TIMEOUT_MIN` (40), `STMP_BASE` (`ios/native-client`), `STMP_RUN_STATE`
(for `P<n>` tickets), `STMP_SEED_DIRS`. Every step runs under a wall-clock cap with one automatic retry; a queued run prints
`DOD WAIT slot` every 5 minutes. A package resolve or network failure prints `DOD FAIL network`, which is not charged.

Safety rules the script enforces: `CODE_SIGNING_ALLOWED=NO` always; it refuses `archive`, `-exportArchive`, `altool` and
`notarytool`; it never touches the main checkout; it never pushes. Agents build for the simulator only.

The context step refuses to run when the git common directory is inside `/Users/mohamed/Developer/STMP.AI/` (D40: the run uses
its own clone). `STMP_ALLOW_MAIN_COMMON_DIR=1` lifts that for a worktree of the main checkout; see the T0 handoff.

## Pinned toolchain

Xcode 27.2, Swift 6.4.2, xcodegen 2.46, simulator "iPhone 17" on iOS 26.5 (`scripts/sim.env`). The runtime is pinned and never
downloaded or removed by a script; a missing runtime is `DOD FAIL runtime`. The deployment target is iOS 17.0.

## Adding a file

Sources are directory based, so adding a Swift file never edits `project.yml` or `Package.swift`. Put it in your own prefix
(`scripts/ownership.tsv` lists who owns what; `dod.sh` fails on a path nobody owns for your ticket). Every ticket also owns
`changes/T<n>.md`: `- ` bullets of at most two sentences, no names, no history. Never edit `CHANGELOG.md` or
`Config/Version.xcconfig`; the merger does.

## Placeholders

T0 left a compiling, fail-closed placeholder in every module: `<Module>Plugins.make(_:)` returning plugins whose handlers throw
`internal_error` "not built yet", `declaredRoutes` with the FULL route list, one `Inert<Seam>` per seam, a feature entry point
per screen and a `Composition/<Area>+Live.swift` per area. The owner rewrites the file. The frozen part is the signature: the
`<Module>Plugins` factory, the feature entry point (`HomeScreen(env:)`, `LogDetailScreen(env:id:)`, ...) and
`<Area>Live.make(_ ctx: CompositionContext)`. A missing seam is an amendment ticket `A<n>`, never an edit to `STMPCore`.

## Amendment rules

1. Try an extension or a new protocol in your own module.
2. Otherwise write `SEAM-REQUEST: <what and why>` in your handoff and continue against the current seam.
3. The supervisor batches requests into `A<n>`, the only writer of `STMPCore` after T0. It has its own worktree, branch, DoD and
   review, and merges serially. Affected tickets rebase when told to.

`STMPCore`, `STMPTestSupport/Core`, `ContractTests`, `scripts/`, `project.yml` and `Package.swift` are frozen after T0.

## Rules every ticket follows

- **XCTest only (D60).** `import Testing` fails the DoD. Timing goes through the injected `StmpClock` and `TestClock`; no
  `Task.sleep`, `Thread.sleep`, `usleep` or `sleep(` in tests; an expectation waits at most 30 s.
- **Naming (D108).** Internal top-level types in `Features/`, `Platform/` and `Composition/` are prefixed with the area
  (`HomeViewModel`, `VaultKeychainStore`) or nested in `enum <Area>`. Test classes are prefixed with the area. Helpers are
  `private`. `scripts/symbols-check.sh` fails on a duplicate top-level type name.
- **Isolation (D6, D54, D90).** The package is nonisolated by default; the app target is MainActor by default and sets
  `SWIFT_APPROACHABLE_CONCURRENCY = NO` with no `NonisolatedNonsendingByDefault`. A `Platform/**` type that conforms to an
  `STMPCore` seam is declared `nonisolated` and is an actor or a lock-guarded `Sendable` final class. A blocking entry point is
  `@concurrent`. `Platform/Attendance/AttendancePlaceholder.swift` is the worked example; `IsolationTests` call it from
  `@MainActor` and assert `dispatchPrecondition(condition: .notOnQueue(.main))` inside its body (D126).
- **Multi-reader state goes through `Broadcast` (D53, D87, D123)**, never a shared `AsyncStream`. `current` is synchronous;
  `updates()` yields `current` first. `onTermination` takes the lock only to remove its own key; `finish()` finishes the
  continuations outside the lock.
- **Synchronous seams on actors (D122).** `PolicyProviding.current()`, `CallerRegistry.state(of:)`, `PluginToggles.isEnabled`
  and `VaultService.isUnlocked()` read a published snapshot: the actor owns writes and, after each commit, publishes a
  `Result<Value, StoreReadError>` into a `nonisolated let` `Broadcast`. The accessor throws on a failure value, so an
  unreadable file is a cached failure. `@unchecked Sendable` is never the answer (the only allowed uses are `SecretBytes`, with
  `// SAFETY:`, and the NIO seam).
- **Views never read the wall clock (D70).** Use `env.now()` and `env.calendar`; no `Date()`, `Date.now`, `style: .relative`.
- **Untrusted request text** is shown only through `UntrustedText`. Secrets live in `SecretBytes` and are never logged.
- **UI test identifiers** extend `AXID` in `Features/<Feature>/AXID+<Feature>.swift`: `nonisolated` string constants, Foundation
  only (the file also compiles into `STMPUITests`).
- **Snapshots** go only through `assertStmpSnapshot(of:named:)` (`App/Tests/Support/SnapshotHelper.swift`). It forwards your
  `#filePath`, `#function` and `#line`, so references land in `__Snapshots__/` beside your test. The matrix is light and dark at
  `.large` and `.accessibility1`. Record mode comes from `SNAPSHOT_TESTING_RECORD`: `never` by default, `missing` only under
  `dod.sh --record`, so a missing reference fails a plain run.
- **UI test launch options** (DEBUG only; a Release binary contains none of the names): `-STMPTestBind loopback`,
  `STMP_TEST_PORT`, `-STMPStorageRoot <dir>`, `-STMPResetState`, `-STMPSkipOnboarding`, `-STMPSeedPolicy <absolute path>`.

## Not in v1

AI summaries (the seam is a no-op and `aiBackend` is read as OFF), imported Ed25519 or RSA SSH keys, Google and broker
end-to-end tests, the broker's resolved-IP check (an allowlisted hostname that resolves to a private address is reachable),
LAN binding, audit tamper evidence. Android notifications answer 501 `not_supported_on_ios`; there is no Updater (TestFlight
updates the app).

## Risks the owner should know about

- **R11, unattended approval.** A NOTIFY request is approved by doing nothing, and a user who turns the Face ID switch off
  weakens SECURE. The approval prompt is never shown while the app is not active: the request is denied.
- **R17, Face ID by default.** A fresh install resolves every request except `help` to SECURE, which approves with tap then Face
  ID (D131). Turning "fingerprint for modals" off replaces Face ID with a 1.2 s hold.

Device-only checks (Secure Enclave key creation, Face ID, Local Network permission, Guided Access) are listed in
`tools/DEVICE-CHECKLIST.md` (T17 writes it).

## T0 spikes

Run on 2026-11-14 with Xcode 27.2, Swift 6.4.2, simulator iPhone 17 on iOS 26.5.

- **R1, Keychain on the unsigned simulator.** SPIKE_R1_RESULT
- **NIO and swift-nio-ssh on the iOS Simulator.** `swift-nio` 2.105.0 and `swift-nio-ssh` 0.15.1 (pinned `exact:`) resolve
  with `swift-crypto` 5.1.1, `swift-asn1` 1.7.4, `swift-atomics` 1.3.1, `swift-collections` 1.7.6 and `swift-system` 1.9.0 (the
  computed runtime closure, D124). The package builds for the macOS host and, through the app target, for
  `generic/platform=iOS Simulator` with Swift 6 language mode, complete strict concurrency and warnings as errors.
- **`init(secureEnclaveP256Key:` (D88).** `grep -r "init(secureEnclaveP256Key:" swift-nio-ssh/Sources` finds
  `NIOSSHPrivateKey.swift:56`, `public init(secureEnclaveP256Key key: SecureEnclave.P256.Signing.PrivateKey)`, inside
  `#if canImport(Darwin)`. It exists in 0.15.1, so `STMPSSH` builds the key as
  `NIOSSHPrivateKey(secureEnclaveP256Key:)` on a device and `NIOSSHPrivateKey(p256Key:)` on the simulator, as D88 says.
  No fallback is needed.
