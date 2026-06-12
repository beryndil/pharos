# Pharos — Testing Matrix (spec §4.3)

On-device test checklist. Work through this before production submission.
The automated subset (JVM unit + Robolectric) is covered by the CI gate and runs on every
commit. Items marked **[DEVICE]** require real hardware; emulator results are supplementary.

---

## Alarm reliability (§4.3) — [DEVICE]

Run on ≥3 OEM brands. Minimum: Samsung Galaxy, Google Pixel, Xiaomi.
Optional: Motorola, OnePlus, OPPO, Vivo, Honor.

| Test | Samsung | Pixel | Xiaomi | Other |
|------|---------|-------|--------|-------|
| Alarm fires after device reboot | | | | |
| Alarm fires after app update/reinstall | | | | |
| Alarm fires during overnight Doze (device idle, unplugged) | | | | |
| Alarm fires after manual timezone change (Settings → Date & time) | | | | |
| Alarm fires at correct local time after DST spring-forward | | | | |
| Alarm fires at correct local time after DST fall-back | | | | |
| Alarm fires after manual clock change (time set backwards then forwards) | | | | |
| OEM battery optimization unrestricted: alarm still fires | | | | |
| Test reminder button (onboarding + reliability dashboard) produces an alert | | | | |

**OEM battery killer notes:**
- Samsung: Settings → Battery → Background usage limits → check for Sleeping apps
- Xiaomi: Settings → Battery & performance → Power saving → Autostart (enable for Pharos)
- Huawei/Honor: Settings → Battery → Launch (Manage automatically → Manage manually; enable all)
- OPPO/Vivo: Settings → App management → Battery savings → No restrictions

Reference: https://dontkillmyapp.com

---

## Dose state machine (§4.3) — [DEVICE / automated]

| Test | Status | Notes |
|------|--------|-------|
| Missing a dose at 08:00 has no effect on the 12:00 dose (fires normally) | Automated | `DoseStateMachineTest` |
| Snooze does not erase or delay future dose instances | Automated | `DoseStateMachineTest` |
| Snoozed dose becomes MISSED when the miss window closes, not at the snooze wake | Automated | |
| Interval schedule recomputes correctly after pause/resume | Automated | `ScheduleEngineTest` |
| Tapering schedule advances through phases correctly | Automated | `ScheduleEngineTest` |
| PRN daily-max warning fires on the Nth log (non-blocking) | Automated | `DoseStateMachineTest` |
| PRN dose never reaches SCHEDULED or MISSED state | Automated | |

---

## Backup and restore (§4.3) — [automated + manual verification]

| Test | Status | Notes |
|------|--------|-------|
| Full backup → restore produces identical regimen | Automated | `BackupRestoreTest` |
| Corrupt backup file is rejected before any plaintext is returned | Automated | `BackupCryptoTest` |
| Wrong-passphrase backup is rejected cleanly (no crash, no partial import) | Automated | |
| Restore from pre-wipe backup on a fresh install produces correct regimen | [DEVICE] | Install fresh, restore, verify alarms re-arm |
| Post-wipe restore offer appears on first launch with an empty regimen | [DEVICE] | |

**Note (TODO.md):** `BackupRepository.restore` does not yet call `AlarmCoordinator.reScheduleAll()`
after import. Dave must manually trigger a test reminder after restoring to verify alarms are armed.
This is logged as a Slice 11 hardening item.

---

## Drug database update (§4.3)

| Test | Status | Notes |
|------|--------|-------|
| CDN drug-DB update preserves the full user regimen (no data loss) | Automated | `DrugDbUpdaterTest` |
| Failed / corrupt CDN download leaves the prior DB in place | Automated | `DrugDbUpdaterTest` |
| Bad Ed25519 signature is rejected before the DB is swapped | Automated | `ManifestVerifierTest` |

**Note:** CDN_BASE_URL is blank until Dave provisions Backblaze B2 + Cloudflare (TODO.md).
The update worker skips gracefully when the URL is blank.

---

## Accessibility (Law 10, §4.4) — [DEVICE, TalkBack]

| Test | Status | Notes |
|------|--------|-------|
| Add a medication end-to-end under TalkBack: every interactive element announced | [DEVICE] | S10-A9 |
| Mark a dose Taken / Snooze / Skip with TalkBack: dose-name context in announcement | [DEVICE] | S10-A2 |
| View schedule and dose history under TalkBack: no unlabeled controls | [DEVICE] | |
| Navigate onboarding end-to-end under TalkBack | [DEVICE] | |
| Navigate reliability dashboard under TalkBack | [DEVICE] | |
| Navigate legal screen under TalkBack | [DEVICE] | |
| All screens at font scale 2.0×: no text truncation, no layout breakage | [DEVICE] | |
| High-contrast / dark mode: dose state labels legible (WCAG AA ≥ 4.5:1) | [DEVICE] | |
| All touch targets ≥ 48dp confirmed visually at normal font scale | Automated (lint) | |

---

## Full-screen intent (§3.4, §4.1) — [DEVICE]

| Test | Status |
|------|--------|
| DUE dose alert takes over the screen on a locked device | [DEVICE] |
| DUE dose alert shows action buttons (Open Pharos) | [DEVICE] |
| Android 14 canUseFullScreenIntent() denial path correctly surfaces the settings link | [DEVICE] |
| Play Console USE_FULL_SCREEN_INTENT justification submitted and approved | [Dave] |

---

## Staged rollout gate

Before advancing from internal → closed → open → production:
- Zero new ANRs introduced by this version.
- Crash rate ≤ 1% (aggregate from manual beta testers; no third-party SDK required).
- All [DEVICE] items above completed on ≥3 OEM brands.
- All automated tests green (CI gate).
