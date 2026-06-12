# Feature Spec — Critical Alerts (Pharos)

**Type:** Build slice for Claude Code.
**Belongs to:** BUILD_SPEC.md → Part II (Product Spec) + build-order slice 5 (dose state machine & notification channels).
**Governed by:** CONSTITUTION.md. Read the constitutional constraints below before implementing.

---

## 1. Purpose

Some medications are genuinely can't-miss (insulin, anti-seizure, cardiac, immunosuppressants). For those, a normal reminder that respects silent mode and Do Not Disturb is not enough. This feature lets the user mark specific medications as **critical**, so their reminders break through silent mode and DND the way a continuous glucose monitor (e.g. Dexcom) does — while leaving non-critical reminders on the normal, polite channel.

The override is **per-medication and user-chosen**, never blanket. Most meds are not critical; treating them all as critical trains users to disable the app and invites Play Store rejection.

---

## 2. Constitutional constraints (must obey)

- **Law 1 (dose channel is sacred):** Critical alerts are a tier *within* dose reminding. The critical channel carries critical-med dose reminders only — never donations, refills, re-engagement, or any non-dose content.
- **Law 4 (local / consent):** The DND-override permission is requested explicitly, only when the user opts a med into critical, and is fully under user control.
- **Law 6 (testable + visible):** Critical-override status (per med and globally) and the DND-access permission state must be visible in the reliability dashboard and testable on demand.
- **Law 3 (remind, never advise):** Nothing here changes dose logic or gives advice. It changes only *how loudly* a reminder is delivered.

---

## 3. Behavior

### 3.1 Per-medication critical flag
- Add a boolean `isCritical` to the medication model (default **false**).
- Expose it in the add/edit medication screen with plain-language framing, e.g. *"Critical reminder — break through silent mode and Do Not Disturb for this medication. Use only for medications you must never miss."*

### 3.2 Two-tier channel model
- **Standard dose channel** (existing): normal importance + escalation, respects silent/DND. Used for all non-critical meds.
- **Critical dose channel** (new): bypasses DND, sounds through silent mode, full-screen intent. Used only for `isCritical` meds.

### 3.3 What "break through" means (two independent mechanisms — implement both)
A user who "silences the phone" may be in **ringer-silent/vibrate** *or* in **Do Not Disturb**, or both. The feature must defeat both:
1. **DND mode** → `NotificationChannel.setBypassDnd(true)` on the critical channel (requires DND policy access, see §4).
2. **Silenced ringer** → play the alert sound with **alarm-usage audio attributes** (`USAGE_ALARM`), which sound at alarm volume regardless of ringer mute. Set this as the critical channel's sound.

### 3.4 Graceful failure (mandatory — Law 6)
If DND policy access is **not** granted, the critical channel cannot bypass DND. The app must **not** silently fall back to a normal notification while the user believes they're protected. Instead:
- Show a persistent, visible state: *"Critical override is OFF — grant Do Not Disturb access to enable it."*
- Surface it in the dashboard and on the medication's detail view.
- Still deliver the reminder on the standard channel as a degraded fallback, clearly not the promised behavior.

### 3.5 Dose logic unchanged
Critical status affects delivery only. The dose state machine (DUE/TAKEN/SNOOZED/SKIPPED/MISSED), miss windows, snooze rules, and dose independence are identical to non-critical meds.

---

## 4. Permission flow

- Declare `ACCESS_NOTIFICATION_POLICY` in the manifest.
- Request **lazily**: trigger the request only when the user marks their **first** medication as critical — not during initial onboarding, not for users with no critical meds.
- Prime before the system screen: a short explainer ("so your critical reminders reach you even on silent or Do Not Disturb"), then send the user to the grant screen via `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`.
- Check state with `NotificationManager.isNotificationPolicyAccessGranted()` and reflect it everywhere critical status is shown.
- **Full-screen intent:** the critical alert uses a full-screen intent. On Android 14+ `USE_FULL_SCREEN_INTENT` is restricted; request/verify it (`Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` where applicable). Medication reminders are a qualifying use case — see §6.

---

## 5. Implementation notes (Android)

- **Create the critical channel correctly at creation time.** Bypass-DND can only be set by the app if the channel has DND policy access **and the user hasn't modified the channel since creation**; after user modification it's system/user-controlled only. So configure it fully on first creation; never silently recreate.
- Critical channel config: `IMPORTANCE_HIGH`, `setBypassDnd(true)`, sound set with `AudioAttributes` using `USAGE_ALARM` + `CONTENT_TYPE_SONIFICATION`, vibration enabled, lockscreen visibility appropriate, notifications tagged `CATEGORY_ALARM`, full-screen intent attached.
- **Re-registration:** critical alarms follow the same single-fire-and-reschedule model and the same boot/time/timezone/DST receivers as all other doses (BUILD_SPEC §3.4). No separate scheduling path — only a separate delivery channel.
- **Escalation:** critical alerts escalate at least as aggressively as standard DUE alerts, until taken/skipped or the miss window closes.

---

## 6. Play Store justification (for the listing / declaration)

Critical alerts justify `ACCESS_NOTIFICATION_POLICY`, `USE_FULL_SCREEN_INTENT`, and exact-alarm permissions together. Use language to the effect of: *"This app uses Do Not Disturb access, full-screen intents, and exact alarms solely to deliver time-critical medication reminders that the user explicitly designates as critical, so they are not missed when the device is silenced. The user opts in per medication and can revoke access at any time."* Fold into BUILD_SPEC §4.1 with the other permission justifications. (Note: the Play **Health apps declaration** must also be completed for the app overall — handled at the project level, outside this slice.)

---

## 7. Reliability dashboard additions (Law 6)

Add to the existing dashboard:
- **DND access:** granted / not granted (with one-tap link to the grant screen).
- **Full-screen-intent permission:** granted / not granted.
- **Critical override:** active for N medications (list them).
- **Test critical alert now:** fires a real critical alert through the critical channel so the user can confirm it breaks through on *their* device, in their current silent/DND state.

---

## 8. Testing matrix (do NOT expose to any tester until this passes)

This feature's failure mode is a user trusting it with a life-critical med and not being woken. Verify on real devices before any closed/open tester sees it.

- [ ] Critical alert sounds through **ringer-silent/vibrate** mode.
- [ ] Critical alert sounds through **Do Not Disturb** mode.
- [ ] Critical alert sounds through **both** simultaneously.
- [ ] Verified on **Samsung, Pixel, and Xiaomi** at minimum (add Motorola/OnePlus if available).
- [ ] Verified **overnight in Doze** (screen off, device stationary, hours idle).
- [ ] Verified after **reboot**, **app update**, **timezone change**, and **DST shift**.
- [ ] **DND-access-denied path:** app clearly shows override is OFF and does not pretend otherwise.
- [ ] **Channel-not-modifiable caveat:** confirm behavior if the user edits the channel in system settings.
- [ ] Non-critical meds still respect silent/DND (no accidental override leakage).
- [ ] "Test critical alert now" reflects the device's true current behavior.

---

## 9. Acceptance criteria

- A user can mark any medication critical/non-critical at add or edit time; default is non-critical.
- Marking the first critical med triggers the primed DND-access request; no critical med means the request is never shown.
- Critical-med reminders break through silent mode and DND on the tested OEM devices; non-critical reminders do not.
- When DND access is absent, the app visibly reports the override as OFF and degrades transparently — never silently.
- Dashboard shows DND access, full-screen-intent permission, the list of critical meds, and a working "test now."
- Dose state-machine behavior is byte-for-byte identical between critical and non-critical meds; only delivery differs.
- The full testing matrix (§8) passes before the build is promoted to any tester track.

---

## 10. Out of scope (do not build here)

- No change to dose logic, scheduling math, or the state machine.
- No caregiver alerting (Part V / v2).
- No automatic classification of which meds are "critical" — that is always the user's explicit choice (Law 3).
- No new notification content types on either channel beyond dose reminders (Law 1).
