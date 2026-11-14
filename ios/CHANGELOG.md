# STMP for iOS changelog

Current build: **v0.1.0 (build 1)**. This file has its own version line, separate from Android.

---

## 0.1.0 (build 1) — 2026-11-14 — CURRENT
- The iOS app now has a starting project with a tab bar, the colours, type and spacing used across the app, and the checks that keep later work in step.

## Keeping this file honest

- **Format, always:**

  ```
  ## 0.1.1 (build 2) — 2026-11-15
  - Fixed summary failures reporting a false cause.
  ```

  Heading: `## <version> (build <n>) — <date>`. Body: bullet lines. Nothing else.

- **No names.** Not mine, not anyone's. Release notes describe the software.
- **No history.** Not what was tried, not what failed, not what the old behaviour used to be.
- **No essays.** One bullet per change, one or two sentences.
- **Newest entry at the BOTTOM**, above this section, and only the newest carries `— CURRENT`.
- **Never put a commit hash in an entry.** Find the commit with `git log --oneline --grep "<version>"`.
- **Only the merger edits this file** and `Config/Version.xcconfig`. Tickets write `changes/<id>.md`.
