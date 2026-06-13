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

### A4. v1 feature completion — **DAVE-AUTHORIZED 2026-06-12** (overrides the earlier "new-scope hold")
These were Dave's original v1 intent (cancelled by an earlier AI pass) and are now **explicitly
authorized as in-scope v1 work** with a STANDING GO — do not re-block or re-ask to build these.
Build them the same way as A1–A3 (serialized executors, full gate, ship a tagged signed APK).
Detailed plan: `~/.claude/plans/pharos-v1.3-features.md` + the v1.3.0 block in `TODO.md`.
- **F1 Saved prescribers & pharmacies** — local store (name + phone) with autocomplete + a
  manage/edit/delete screen; split med pharmacy into name + phone.
- **F2 Drug substitution link** — per-med "Substitute for: X" (Law 3 reference framing only);
  suppress the duplicate-ingredient warning between two meds linked as substitutes. Default:
  link + smart warning, no auto-pause of the original.
- **F3 Enriched Today as the home screen** — "next up" timeline + quick-actions row; no second
  landing surface (Today is already `startDestination`).
- **F4 Email meds-list PDF to doctor** — reuse the regimen PDF + ACTION_SEND to the email app,
  health-info confirm first (Law 4, user-initiated export — NOT the v2 caregiver feature).
Ships as **v1.2.x/v1.3.0** (semver continues; conceptually completes the v1 line).

### A5. Settings + Appearance + Accessibility — **REQUIRED v1 (release-gating)**
Plan: `~/.claude/plans/pharos-settings-accessibility.md`. Modeled on Spyglass (settings/about/license).
- **S1 Settings + theme (System/Light/Dark) + in-app TEXT SIZE (Default/Large/XL/Largest) + About +
  License/credits** (data attributions: RxNorm/NLM/RxNav, openFDA — Law 9). Theme + textScale persisted
  in SettingDao; text scale is multiplicative on top of the system font scale.
- **S2 Accessibility hardening — `[LAUNCH-BLOCKER]` (PROGRAMMING_STANDARDS §8 / Law 10 / spec §2.15):**
  TalkBack `contentDescription` on every actionable control; **no truncation at MAX font scale**;
  ≥48dp targets; icon+text (no color-only). Core flows operable under TalkBack + at max font.
  **Accessibility failures BLOCK the v1 release.** Lived TalkBack/2×-font passes → device TODO §C, but
  ALL code fixes land here — not deferred.

> **Standing rule (mirrors `~/.claude/CLAUDE.md` autonomy contract):** every `[LAUNCH-BLOCKER]` in
> `PROGRAMMING_STANDARDS.md` is an UNCONDITIONAL acceptance criterion for every slice. Read the
> standards doc before dispatching; carry its launch-blockers into each executor prompt as hard,
> gating criteria. Never defer one to a "later"/"device-only" TODO or reframe an already-mandated
> requirement as new scope. Code that meets the standard ships now; only lived verification is a device task.

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
