# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Pharos — Medication Reminder App

Android app (Kotlin / Jetpack Compose). Pre-implementation — the only current artifact is `BUILD_SPEC.md`, the master build spec. Read it top to bottom before writing any code.

## Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Database:** Room over SQLite (two logical DBs — see architecture)
- **Alarms:** `AlarmManager` exact alarms only. **Never WorkManager for reminders** — WorkManager is inexact.
- **Background sync:** WorkManager (CDN drug-DB update only, not time-critical)

## Build commands

*(No project exists yet — populate this section when the Gradle project is initialized.)*

## The Ten Laws (Part I — outrank everything)

These are hard invariants. No feature, shortcut, or deadline overrides them.

1. **Dose channel is sacred** — carries dose-due alerts only; nothing else (no refill, no marketing, no re-engagement).
2. **Safety-critical features are free, forever** — unlimited meds, reminders, warnings, backup/restore. Never paywall these.
3. **The app reminds, records, displays reference — it never advises** — no "skip," "double up," "take both now," or any dose-combining instruction. Warnings point to doctor/pharmacist only.
4. **Health data is local by default** — no off-device transmission without explicit, specific, opt-in consent.
5. **No ads, no data brokerage, no tracking** — no third-party ad/analytics/attribution SDK.
6. **Every alarm is testable; reliability is visible** — ship a "test reminder now" path and the reliability dashboard (§2.13).
7. **Every user has a free recovery path** — manual encrypted backup/restore and exportable list, free forever.
8. **Caregiver access is opt-in, minimal, instantly revocable** — do not build in v1 (Part V).
9. **Drug data is sourced, versioned, reversible** — every record shows source + freshness date; DB updates are atomic and rollback-safe.
10. **Accessibility is a launch gate** — TalkBack labels, sp units, ≥48dp targets, no color-only warnings. Failures block release.

## Architecture

### Two logical databases

| DB | R/W | What it holds |
|---|---|---|
| Drug reference DB | Read-only (CDN-replaced atomically) | RxNorm ingredients/products/RxCUIs, cached openFDA label text (with fetch timestamp) |
| User regimen DB | Read/write (never touched by CDN updates) | Medications, schedules, dose instances + states + timestamps, refill records, settings |

Dose history is **append/transition-only** — never overwrite a past record.

### Alarm engine contract

- Single-fire-and-reschedule: schedule the next exact alarm on every fire.
- Re-register on: `BOOT_COMPLETED`, app update/reinstall, `TIME_SET`, `TIMEZONE_CHANGED`, and daily DST/midnight rollover.
- Graceful degradation: if exact-alarm permission is unavailable, fall back to `setWindow` — degrade timing, never drop the reminder.
- DST/timezone math correctness is a **launch gate** (§4.3) — wrong-hour dose after a DST shift is a safety bug.

### CDN drug-DB pipeline

RxNorm trimmed to needed fields → compiled to compact SQLite → pushed to Backblaze B2 behind Cloudflare (your CDN). App pulls from your CDN, never from NLM directly. Updates: download → validate → swap atomically; failed download leaves prior DB in place.

## Build order (Part VI)

Build one slice at a time; verify before the next. Slices 4 and 5 are the highest-risk.

1. Foundations & schema (two-DB structure, trimmed RxNorm bundle, append-only regimen schema)
2. Medication identity & entry (strength/form confirmation, RxNorm resolution, free-text fallback, duplicate-ingredient warning)
3. Schedules (fixed, days-of-week, interval, window, PRN, temporary, taper; pause/resume/end-date)
4. **Alarm engine & reliability** (single-fire-and-reschedule, all re-registration receivers, full-screen DUE alert, setWindow fallback, test-reminder path, DST/timezone math)
5. **Dose state machine** (DUE/TAKEN/SNOOZED/SKIPPED/MISSED, sacred dose channel, escalation, snooze + miss-window logic, independent dose instances)
6. Onboarding & permission flow + reliability dashboard
7. Refill tracking (own channel, separate from dose channel)
8. Drug reference (per-drug openFDA fetch-and-cache; source + freshness in UI)
9. Backup / restore / export (encrypted JSON, post-wipe restore offer)
10. Accessibility pass (all core flows)
11. Launch gates (Play compliance, legal text, testing matrix, staged rollout)

## v2 Quarantine — do not build, do not scaffold

Cloud sync, caregiver dashboard, full pairwise interaction engine (licensed data), i18n/non-US drug DBs, Wear OS, widgets, custom alarm sounds, pharmacy integration, time-zone travel prompt UX, donations/tip-jar UI.

When a slice "naturally" leads toward any of these — stop.

## Key behavioral rules (from spec)

- **Dose instances are independent.** Missing 08:00 has zero effect on 12:00 — it fires normally as its own instance.
- **Miss window default:** 60 minutes after due time OR start of the same medication's next scheduled dose, whichever comes first.
- **Snooze default:** 15-minute interval; cannot push a dose past its miss window.
- **Duplicate-ingredient warning** is higher priority than drug-drug interaction checking for v1.
- **PRN doses:** no SCHEDULED/MISSED states; user-initiated logs only. Optional daily-max warning (non-blocking, never forbids).
- **Free-text fallback meds** get reminders and refill tracking but no duplicate/interaction/label reference; app says so plainly.
