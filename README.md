# Pharos

A personal medication reminder for Android. Private, offline-first, and free.

## What it is

Pharos helps you remember to take your medications on time. It fires exact-time alarms —
even in Doze/battery-saving mode — and records what you did with each dose. Your data stays
on your device, encrypted at rest. No account, no cloud, no ads.

## The Ten Laws

These are hard invariants. No feature overrides them.

1. **Dose channel is sacred** — dose reminders and nothing else on that channel.
2. **Safety-critical features are free, forever** — reminders, drug warnings, backup/restore.
3. **The app reminds, records, displays reference — it never advises** — no "skip," "double up," or dose-combining instructions.
4. **Health data is local by default** — no off-device transmission without explicit opt-in.
5. **No ads, no data brokerage, no tracking** — no third-party analytics/attribution SDK.
6. **Every alarm is testable; reliability is visible** — test-reminder path and reliability dashboard ship with the app.
7. **Every user has a free recovery path** — encrypted backup and restore, free forever.
8. **Caregiver access is opt-in, minimal, instantly revocable** — not built in v1.
9. **Drug data is sourced, versioned, reversible** — every record shows source and freshness; DB updates are atomic and rollback-safe.
10. **Accessibility is a launch gate** — TalkBack labels, sp units, ≥48dp targets, no color-only warnings.

## Status

v1.0.0 — all ten build slices complete. On-device test matrix and Play Console submission
are the remaining Dave tasks (see `docs/testing-matrix.md` and `TODO.md`).

## Build

### Prerequisites

- JDK 21 (not 26 — AGP 8.13.2 breaks on JDK 26)
- Android SDK with compileSdk 35 tools
- SDK at `~/.local/share/Android/Sdk` (or update `env.sh`)

### Environment setup

```bash
source ./env.sh
```

`env.sh` sets `ANDROID_HOME`, `ANDROID_USER_HOME`, `GRADLE_USER_HOME`, and `JAVA_HOME`.
It is gitignored. If it is missing, recreate it from `DECISIONS.md` entry A5.

### Debug build (local verification)

```bash
./gradlew --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

All three must be green before any commit.

### Release build (minified, R8)

```bash
./gradlew --no-daemon :app:assembleRelease
```

Without a `keystore.properties` file, falls back to the debug keystore for signing.
To sign with the real release keystore, create `keystore.properties` at the project root:

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

`keystore.properties` is gitignored. The real keystore and Play App Signing enrollment are
Dave's tasks — see `TODO.md`.

## Architecture

Single-Activity, Jetpack Compose, MVVM. Two Room databases:

- **RegimenDatabase** — read/write user data (medications, schedules, dose history, refills).
  Encrypted at rest with SQLCipher + Android Keystore (Tink).
- **DrugRefDatabase** — read-only drug reference (RxNorm, openFDA labels). Replaced atomically
  by CDN updates; never touches the regimen DB.

`AlarmManager` exact alarms only for dose reminders. WorkManager for the non-time-critical
CDN drug-DB update and low-supply check jobs.

## Project layout

```
app/src/main/java/com/beryndil/pharos/
├── alarm/          Alarm engine: scheduler, receiver, re-registration, DUE alert
├── backup/         Encrypted backup/restore/export (Argon2id + AES-256-GCM)
├── core/           Time math (DoseClock), crypto, DB, utilities
├── data/           Room DAOs, entities, repositories
├── dose/           Dose state machine, Today screen, dose history
├── legal/          Legal screen (ToS, Privacy, Medical Disclaimer)
├── medication/     Medication list, add/edit flow, duplicate detection
├── onboarding/     First-launch permission priming
├── reference/      Drug reference screen (openFDA label cache)
├── refill/         Refill tracking, low-supply WorkManager worker
├── reliability/    Reliability dashboard (Law 6)
├── schedule/       Schedule types and ScheduleEngine
└── ui/             Theme, navigation graph
```

## Docs

- `BUILD_SPEC.md` — master build specification (Part I–VI)
- `PROGRAMMING_STANDARDS.md` — coding standards (gate, not advice)
- `DESIGN.md` — design standards (Apple-grade, Law 3 framing)
- `DECISIONS.md` — all architectural and spec-decision log entries
- `PIPELINE.md` — autonomous build pipeline operating contract
- `TODO.md` — deferred items and Dave-only walls
- `docs/play-listing.md` — Google Play store listing copy + permission justifications
- `docs/testing-matrix.md` — on-device testing checklist (spec §4.3)
