# Pharos — Remaining Work (execution backlog)

Single source of truth for finishing Pharos. Owned by the build orchestrator. Work the
**Section A** items top-to-bottom; **B** and **C** are NOT code-workable here — log and skip.

## Current state (2026-06-12)

- Git: `origin/main` HEAD = **c5f39c5**, tag `v1.0.1`. Private repo `github.com/beryndil/pharos`.
- All 11 v1 slices built; app runs on an emulator. **252 unit tests, gate green.**
- Since v1.0.1: SQLCipher launch-crash fix, drug-ref seed-column fix + 3 error-handling
  layers, teal palette, onboarding back button, onboarding→medication-list routing.

## Read first (context — do not skip)

`BUILD_SPEC.md` (the v1 spec + Ten Laws), `FEATURE_critical_alerts.md` (Section A1 spec),
`PROGRAMMING_STANDARDS.md`, `DESIGN.md` (Apple-clean, no AI-isms, teal accent — gate UI on
it), `DECISIONS.md` (all prior calls + the slice decision tables), `TODO.md` (granular
deferred items, the authoritative detail behind this list), `PIPELINE.md` (roles/protocol).

## Build environment (every executor's first action)

```
cd /home/beryndil/Projects/active/pharos && source ./env.sh
```
Sets the redirected `ANDROID_HOME`/`ANDROID_USER_HOME`/`GRADLE_USER_HOME` + **JDK 21** (JDK 26
breaks AGP). The real `~/.android`, `~/.gradle`, `$HOME` root are read-only. Always
`./gradlew --no-daemon`, gates in the FOREGROUND, timeout-wrapped.

**Gate policy (this box is 4-core and may be running an emulator + mirror):**
- Iteration / per-item gate: `:app:lintDebug :app:testDebugUnitTest :app:assembleDebug`
  (light). Lint zero-error; never weaken `lint.xml`.
- Before any version **tag**: add `:app:assembleRelease` (R8) — heavy, run it then.
- Do NOT run multiple Gradle builds concurrently; serialize.

## Execution protocol

- One item at a time (shared worktree). Implement → light gate green → commit (conventional
  commit) → push. Log every code decision in `DECISIONS.md`; move any newly-deferred item to
  `TODO.md`. UI items additionally pass the `DESIGN.md` review.
- Every user-facing string in `strings.xml` (lint enforces). No PHI in logs. `FLAG_SECURE`
  stays on health screens. Accessibility: TalkBack labels, ≥48dp, icon+text not color-only.
- Add/extend unit tests for every behavioral change (the device-only parts go to TODO as
  Dave items — never fake them).
- Decide code calls autonomously; never ask A-or-B. `BLOCKED` only for the Section-B/C walls.

---

## SECTION A — buildable now (work these)

### A1. Critical Alerts  ← biggest item; full spec in `FEATURE_critical_alerts.md`
Per-med `isCritical` flag (DB migration, schema export, migration test) · critical
notification channel (bypass-DND, `USAGE_ALARM` audio, full-screen, `CATEGORY_ALARM`,
created-once correctly) · route critical meds' DUE alerts to it, others stay standard ·
lazy `ACCESS_NOTIFICATION_POLICY` request on the FIRST critical med (primed → settings) ·
transparent "Critical override is OFF" degrade in dashboard + med detail when DND access
absent (never silent) · dashboard additions (DND access, full-screen-intent state, list of
critical meds, "test critical alert now") · Play justification text → `docs/play-listing.md`.
Dose logic byte-for-byte unchanged. Unit tests for: flag persistence+migration, channel
selection, DND-denied degrade, lazy permission on first critical med. §8 device matrix → TODO.

### A2. Functional gaps (real behavior missing)
- **Restore → re-arm alarms.** After backup restore, call `AlarmCoordinator.reScheduleAll()`
  (break the backup→alarm dependency cleanly via a post-restore callback / ctor param).
- **Restore → re-enqueue WorkManager** (`LowSupplyCheckWorker`, `DrugDbUpdateWorker`); cancel
  stale jobs for old med IDs.
- **PRN "Log dose" UI.** Backend (`DoseStateMachine.logPrnTaken` + daily-max warning) exists
  but PRN meds have no Today row / no log affordance. Surface one; render the non-blocking
  daily-max warning on `PrnLogResult.exceedsMax`.
- **Refill-by date picker.** Wire `DatePickerDialog` → `RefillEvent.ConfirmSetRefillByDate`
  (dialog skeleton already present).

### A3. Polish / quality
- **Teal launcher + splash icon** (currently the old blue `ic_launcher_background`); retint
  to the brand teal, keep the simple geometric "+" mark (DESIGN.md — flat, no gradient).
- **12/24-hour time** from `DateFormat.is24HourFormat(context)` (hardcoded 12h in
  `ScheduleSection` TimePicker); proper clock icon (`Icons.Outlined.AccessTime`).
- **Monochrome notification small icon** (24dp white-on-transparent); stop reusing
  `ic_launcher_foreground` in `FullScreenDoseNotifier`.
- **Multi-DUE notifications.** Two meds DUE the same minute share one tray slot; key
  per-dose notification ids so both are individually actionable.
- **Dashboard permission refresh on resume** (`LaunchedEffect(lifecycleState)`).
- **Per-screen FLAG_SECURE** refinement (currently global; restrict to PHI screens).
- **StrongBox path** version-gated `@RequiresApi(28)` for the Tink wrapping key.
- Minor: PDF export word-wrap, "share backup" snackbar action, dose-history cause line,
  INTERVAL explicit anchor-time field, boot-trigger human-readable label in dashboard.

---

## SECTION B — needs Dave (account / payment) — DO NOT attempt; keep in TODO
Drug-DB CDN (Backblaze B2 + Cloudflare) + real URL · release signing keystore + Play App
Signing · real Ed25519 manifest keypair (replace RFC test vector) · SPKI cert pinning (needs
CDN domain) · Google Play Console (Data Safety + Health declaration, permission
justifications, store listing) · host privacy policy at a public URL · real RxNorm dataset.

## SECTION C — needs a real phone (Dave's test pass, spec §4.3) — keep in TODO
Alarm reliability (reboot, Doze/standby, time/timezone, DST, OEM battery-killers ≥3 brands) ·
full-screen DUE alert visuals over lockscreen · TalkBack lived pass · 2× font-scale pass ·
contrast pass · encrypted-DB cold-launch smoke.

---

## Milestones / tagging
- After A1 + A2 land and the **full** gate (incl `assembleRelease`) is green → bump **v1.1.0**,
  commit at the version bump, tag, push.
- A3 polish can ship as v1.1.x.
- Pre-1.0 CI policy does not apply (we're ≥1.0.0) — but do NOT add GitHub Actions unless Dave
  asks; the local gate is the gate.

## Report
Post progress + final completion to Dave's Pharos session
(`ses_9e7caaed0937185511b2f4d0a8d92f6b`). Report when A1+A2 land (v1.1.0) and when A3 is done.
