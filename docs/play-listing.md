# Pharos — Google Play Listing Reference

This document contains the text artifacts Dave needs to paste into the Google Play Console.
It is not code; it is a Dave-only task (Console access and store-listing submission are credential
walls). All prohibited claims are called out; follow the guidance below.

---

## Permission justifications (Play Console declaration)

Paste these into Data safety / Permissions declarations.

### USE_EXACT_ALARM

> This app uses exact alarm permissions only to deliver medication reminders the user creates.
> Each reminder is an alarm the user has scheduled for a specific medication and time.
> Exact timing is medically important: a reminder that fires 15–20 minutes late during a
> Doze/battery-saving window may cause a missed dose. Pharos is a dedicated medication
> reminder app — this usage is consistent with the alarm-app exemption in Play Policy.

### USE_FULL_SCREEN_INTENT

> Pharos uses full-screen intent to display dose-due alerts on the lock screen when a
> medication reminder fires and the device is locked. This is functionally equivalent to
> an alarm clock alert: the user needs to see and act on the reminder immediately,
> without having to unlock the device first. Usage is limited to dose-due alerts only.
> The app does not use full-screen intent for marketing, re-engagement, or any other purpose.

### SCHEDULE_EXACT_ALARM (secondary / fallback)

> Declared as a fallback for devices where USE_EXACT_ALARM is unavailable or revoked.
> The app gracefully degrades to setWindow (10-minute window) when neither exact-alarm
> permission is granted, and never drops a reminder.

---

## Prominent medical-advice disclosure (store listing)

Required by Play policy for health/medical apps. Paste into the "About this app" section.

> Pharos is a personal medication reminder. It does not provide medical advice, diagnose
> conditions, or recommend treatments. Always consult your doctor or pharmacist before
> changing your medication schedule.

---

## Store listing copy — short description (80 chars max)

> Reliable medication reminders. Private, offline, free. No account needed.

## Store listing copy — full description

Paste and review before submission. **Verify no prohibited claims remain** (see review checklist
below).

---

Pharos is a medication reminder app. It helps you remember to take your medications on time.

**What it does:**
- Exact-time alarms for every dose — fires reliably on all Android devices, including in
  battery-saving and Doze mode
- Multiple schedule types: fixed daily times, interval, day-of-week, dose window, as-needed (PRN),
  temporary course, tapering
- Per-dose history — every take, snooze, skip, and missed dose recorded
- Refill tracking — quantity on hand, days remaining, low-supply alerts
- Drug reference — label text from the openFDA database, shown with source and date
- Encrypted backup and restore — your data, protected by your passphrase
- Reliability dashboard — see the status of every permission and alarm path on your device

**Privacy:**
Your health data stays on your device. No account. No cloud sync. No ads. No tracking.
All medication data is encrypted at rest. Android device backup is disabled for health data.

**Free, no limits:**
All features — reminders, history, drug reference, backup, and restore — are free with no
medication cap, no subscription, and no paywall.

**Medical disclaimer:**
Pharos is a reminder tool. It does not provide medical advice. Always consult your healthcare
provider before changing your medication schedule.

---

## Review checklist before Console submission

Work through each item and confirm:

- [ ] No claim that the app "prevents missed doses" — prohibited (a reminder can fail; the
      claim implies a guarantee). Permitted: "helps you remember to take medications on time."
- [ ] No claim that the app "ensures safety" or "keeps you safe" — prohibited. Medical
      outcomes are not guaranteed by a reminder app.
- [ ] No claim of medical diagnosis, treatment recommendation, or clinical outcome.
- [ ] Medical disclaimer is present and prominent in the store listing.
- [ ] Privacy policy URL is filled in (required for all apps declaring health-related
      permissions). Host the privacy policy text at a stable URL before submission.
- [ ] USE_EXACT_ALARM justification filed.
- [ ] USE_FULL_SCREEN_INTENT justification filed and reviewed (Android 14+ gate).
- [ ] Data Safety section completed: no data collected; data encrypted on-device;
      no sharing with third parties.
- [ ] Health Apps Declaration completed if the Play Console prompts for it.
- [ ] targetSdk = 35 (confirmed in app/build.gradle.kts).
- [ ] App tested against the §4.3 testing matrix before submitting to production.

---

## Staged rollout plan (spec §4.3)

1. Internal testing — Beryndil team / personal devices.
2. Closed testing (alpha) — invite-only; prioritize veterans, caregivers, elderly users.
3. Open testing (beta) — broader audience; monitor ANRs and crash rate.
4. Production — staged rollout 10% → 50% → 100% with monitoring at each step.
