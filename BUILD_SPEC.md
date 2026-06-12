# Medication Reminder App — v1 Build Specification

**Brand:** Beryndil
**Document status:** Master build spec. Self-contained. Read top to bottom before writing code.
**Audience:** The implementer (Claude Code) and the director (Dave).

---

## How to use this document

This is the single source of truth for v1. It has six parts:

- **Part I — Constitution.** The invariants. What must always stay true. These outrank everything below.
- **Part II — Product Spec.** What v1 does, as behavior.
- **Part III — Technical Architecture.** How it's built.
- **Part IV — Launch Gates.** Non-feature work that must be done before release.
- **Part V — v2 Quarantine.** What we are deliberately NOT building yet.
- **Part VI — Build Order.** The sequence to build it in.

**Rules of precedence:** When Part II–VI conflicts with Part I, Part I wins. When a feature is not in Part II and not in the build order, it does not get built — see Part V.

**`◆ DECISION` markers** flag choices that are the director's call. Each has a proposed default so the spec is buildable as written, but the director may override any of them before that slice is built.

---

# PART I — THE CONSTITUTION

*(Reproduced in full from CONSTITUTION.md. These are invariants, not features. When any proposed feature conflicts with a law, the feature loses. A law may only be changed by deliberately editing this document — never overridden silently under deadline or metric pressure.)*

**1. The dose channel is sacred.** The dose-reminder notification channel carries dose reminders and nothing else. *Forbids:* donations, re-engagement, feature announcements, refill upsells, low-supply alerts, caregiver prompts, or any non-dose message on that channel.

**2. Safety-critical features are free, forever.** Reliable reminding, unlimited meds, duplicate/interaction warnings, side-effect reference, and backup/restore are free with no cap. *Forbids:* capping medication count, gating reminders, paywalling any warning, or making the only backup path paid.

**3. The app reminds, records, and displays reference — it never advises.** *Forbids:* "skip this dose," "take it now," "double up," "this is safe," combining missed doses into one instruction, or any phrasing that crosses from reminding into advising. Warnings point outward: "check with your doctor or pharmacist."

**4. Health data is local by default.** Data leaves the device only with explicit, specific, opt-in consent for a named purpose. *Forbids:* any off-device transmission (analytics, crash reports, backup, sync) that is on by default or bundled into broad consent.

**5. No ads, no data brokerage, no tracking.** *Forbids:* ad networks, data sales or "partner sharing," and any third-party advertising/analytics/attribution/fingerprinting SDK.

**6. Every alarm is testable, and reliability is visible.** *Forbids:* shipping any reminder path the user can't test on demand; hiding permission/battery/auto-start/last-fired status from the user.

**7. Every user has a free recovery path.** Free users get manual encrypted backup-and-restore and an exportable list. *Forbids:* making basic backup or restore paid-only.

**8. Caregiver access is opt-in, minimal, and instantly revocable.** *Forbids:* monitoring on by default, exposing more than authorized, or access the patient can't sever instantly and alone.

**9. Drug data is sourced, versioned, and reversible.** *Forbids:* unversioned/irreversible database pushes; presenting reference data without source and freshness date.

**10. Accessibility is a launch gate, not a later polish.** *Forbids:* shipping with unlabeled controls, color-only warnings, non-scalable text, touch targets under 48dp, or any core flow that fails under TalkBack or large fonts.

---

# PART II — PRODUCT SPEC (v1)

## 2.1 The v1 boundary

v1 is a **single-device, offline-first, private medication reminder.** Everything below is in scope. Anything not listed here is Part V.

In scope: medication list (unlimited) · reliable exact alarms · the dose state machine · fixed/interval/windowed/PRN/temporary/tapering schedules · pause/resume/end-date · non-destructive dose history · duplicate-ingredient warning · drug reference (label/side-effect) when available · refill tracking · free local encrypted backup/restore · reliability dashboard · accessibility · no ads · no account.

## 2.2 Core concepts (behavioral model)

- **Medication** — a thing the user takes. Has identity (name, ingredient, strength, form), not just a name.
- **Schedule** — the rule that generates dose instances for a medication (fixed times, interval, window, PRN, temporary course).
- **Dose instance** — a single occurrence: "metoprolol succinate 25mg, 2026-06-12 08:00." Each instance is **independent** and has its own state. This independence is a hard requirement of Law 3.
- **Regimen** — the user's full set of active medications and schedules.

## 2.3 Adding a medication — identity and verification

The app must not trust a bare typed name. On add, the user resolves and confirms:

- **Name** (resolved against the local drug DB; free-text fallback allowed — see 2.11)
- **Ingredient(s)** (from the resolved record; drives duplicate detection)
- **Strength** (e.g., 25 mg) — required
- **Form** — tablet, capsule, liquid, injection, inhaler, patch, drops, cream, other — required
- **Dose amount** (e.g., 1 tablet, 5 mL)
- **Schedule** (see 2.5)
- **Start date**; **end date** (optional)
- **Prescriber / pharmacy** (optional)
- **Purpose** (optional, user's own words)

**The strength/form confirmation is a safety feature, not a formality.** "Metoprolol 25 mg" is ambiguous (tartrate vs. succinate ER, once vs. twice daily). The resolved record must show the user exactly what they selected and let them correct it before saving.

## 2.4 Duplicate-ingredient warning

When a medication is added, compare its active ingredient(s) against every other active medication in the regimen. If an ingredient appears in more than one (e.g., Tylenol + an acetaminophen combo), show a non-blocking warning.

- Phrasing (Law 3): *"Heads up — [Med A] and [Med B] both contain [ingredient]. Taking both could mean a higher total dose than you intend. Check with your doctor or pharmacist."*
- Non-blocking: the user may proceed. The app warns; it never forbids.
- This runs **locally**, against RxNorm ingredient data already on the device. It is independent of drug-drug interaction checking and is **higher priority than interaction checking for v1.**

## 2.5 Schedule types (all required in v1)

1. **Fixed daily times** — one or more clock times per day (08:00, 20:00).
2. **Specific days of week** — e.g., Mon/Wed/Fri.
3. **Interval** — every N hours, optionally bounded by a daily window; next dose computed from the schedule, not from the last-taken time, unless the user chose "from last dose." `◆ DECISION (default: schedule-anchored, with an explicit per-med option for "interval from last dose taken").`
4. **Dose window** — "take between 07:00 and 09:00." The alarm fires at the window start; the dose remains actionable until the window closes (see state machine).
5. **PRN (as-needed)** — no scheduled instances; user logs a dose when taken. Carries an optional **daily-max warning** (see 2.7).
6. **Temporary / course** — start + end date, auto-ends. Required for antibiotics, post-op, etc.
7. **Tapering / multi-phase** — sequences of phases, e.g., "2 tablets/day ×5 days, then 1/day ×5 days" (prednisone packs). Modeled as an ordered list of phases, each with its own dose + duration.

**Pause / resume / end:** any medication can be paused (holds before surgery, etc.), resumed, or ended. Paused meds generate no alarms; resuming recomputes upcoming instances.

## 2.6 The dose state machine (the heart of the app)

Each **scheduled** dose instance moves through these states:

```
            (alarm fires)            (user: Taken)
 SCHEDULED ───────────────► DUE ───────────────────► TAKEN
                             │
                             ├──(user: Snooze)──► SNOOZED ──(re-alert)──► DUE
                             │
                             ├──(user: Skip)────► SKIPPED
                             │
                             └──(miss window closes, no action)──► MISSED
```

State definitions:

- **SCHEDULED** — future dose; exact alarm registered.
- **DUE** — alarm has fired; awaiting user; persistent/escalating alert active (see 2.8).
- **TAKEN** — user confirmed; timestamp recorded.
- **SNOOZED** — user deferred; re-alerts after the snooze interval, returning to DUE.
- **SKIPPED** — user explicitly skipped; logged, no advice given.
- **MISSED** — the miss window elapsed with no user action; logged.

**Hard rules (from Law 3 — non-negotiable):**

- **Dose instances are independent.** Missing the 08:00 dose has **zero effect** on the 12:00 dose. The 12:00 dose fires normally as its own instance.
- **The app never combines doses.** It never says "you missed 8am, take both now," never tells the user to skip, double, or make up a dose. It records state and surfaces history. Any decision about a missed dose belongs to the user and their clinician.

`✅ RESOLVED — Miss window (when DUE → MISSED):` A dose flips to MISSED at **60 minutes after the due time, OR the start of the same medication's next scheduled dose, whichever comes first.** For windowed doses, the miss window is the end of the window. **The 60-minute value is the default but is per-medication configurable** — the user can tighten it for a critical med or loosen it for a low-stakes one. This mirrors the per-med model already used for criticality (§ Critical Alerts): miss tolerance, like criticality, is a property of the individual medication, not a global setting.

`✅ RESOLVED — Snooze rules:` Snooze re-alerts every **15 minutes**. The user may snooze **repeatedly with no fixed cap** — but snooze can never push a dose past its miss window: **when the miss window closes, a snoozed dose becomes MISSED regardless.** The closing miss window is the natural and sufficient ceiling, so no arbitrary snooze-count limit is imposed. (Because the miss window is now per-medication configurable, the effective number of available snoozes scales automatically with how long that med stays actionable — a tighter window simply yields fewer snoozes before MISSED.)

## 2.7 PRN doses

- No SCHEDULED/MISSED states; PRN doses exist only as user-initiated "taken" logs.
- Optional **daily-max warning**: if the user set a max (e.g., "max 4/day"), and logs a dose that would exceed it, show a non-blocking warning: *"This is dose [N] today; the maximum you set was [M]. Check with your doctor or pharmacist."* Warn, log, never block (Law 3).

## 2.8 Notifications, channels, and escalation

- **Dose channel** — sacred (Law 1). Carries DUE dose alerts only. Uses a full-screen intent so the alert takes over the screen rather than sitting in the tray. Escalating: re-alerts at increasing intensity until the dose is acted on or the miss window closes.
- **Separate, separately-disableable channels** for everything else: low-supply/refill alerts get their own channel the user can silence without touching dose reminders. Donations and any re-engagement live **in-app only**, never as a notification.
- A DUE dose that is snoozed re-enters DUE and re-alerts after the snooze interval.

## 2.9 Refill tracking

Per medication: quantity on hand · doses per day (derived from schedule) · computed days-until-empty · refill-by date · pharmacy phone (optional) · "request refill" checklist · "picked up refill" action (resets count) · partial-fill support · handle "stopped before bottle empty."

- Low-supply alert uses the **refill channel**, not the dose channel.
- When supply hits zero but doses remain scheduled, still fire dose reminders, but flag "no supply on record" separately. Never suppress a dose reminder because the count says empty (the count may be wrong; Law 1).
- PRN meds deplete unpredictably; show on-hand count but don't compute a confident run-out date.

## 2.10 Drug reference (side effects & interactions)

- **Side-effect / label reference:** fetched per-drug from openFDA/DailyMed on add (when online), cached locally thereafter. Always shown with source + fetch date (Law 9). If unavailable offline and not yet cached, show "reference not available offline" rather than nothing.
- **Interaction reference (v1 scope):** the duplicate-ingredient check (2.4) is the priority. Beyond that, surface each drug's own label "drug interactions" section as reference text. A full pairwise severity-rated interaction engine is **Part V** (requires licensed data; do not build in v1).
- All reference framing points outward and never advises (Law 3).

## 2.11 Free-text fallback & US scope

The drug DB is US-sourced (RxNorm/openFDA). v1 supports **manual free-text entry** for anything not resolved — the med still gets reminders and refill tracking, but **without** ingredient resolution it gets no duplicate/interaction/label reference, and the app says so plainly. International drug databases are Part V.

## 2.12 Backup & recovery (free — Law 7)

- **Manual encrypted backup** to a user-chosen destination (file picker → Downloads/Drive/email-to-self).
- **Restore** from the same, with validation to reject corrupt/partial files.
- **Printable / exportable list** (PDF + CSV) of the full regimen.
- `◆ DECISION — backup format:` **Default: encrypted JSON** (human-inspectable structure, schema-versioned, AES-256-GCM, user-supplied passphrase). SQLite dump is the alternative; JSON is recommended for portability and validation.
- Post-wipe recovery flow: on a fresh install with an empty regimen, the app proactively offers "Restore from backup" rather than waiting for the user to find it.

## 2.13 Reliability dashboard (Law 6)

A user-facing screen showing, in plain language: exact-alarm permission (OK / needs action) · battery optimization (unrestricted / risky) · background/auto-start (OK / risky) · notification permission · full-screen-intent permission · last alarm fired (timestamp) · next scheduled alarm · boot-receiver health · drug DB version + last-updated. Each "risky" item links to the fix. This is both the trust feature and the on-device substitute for analytics (Law 4/5).

## 2.14 Onboarding & permission flow

Sequence, each step primed with *why* before the system dialog (never a permission wall on first launch):

1. Welcome + one-line promise (private, reliable, free).
2. Notification permission (Android 13+ runtime) — primed: "so reminders can reach you."
3. Exact-alarm permission — primed before the scary system dialog: "so reminders fire at the exact time, even in battery-saving mode."
4. Battery-optimization exemption — sends user to settings (no silent API); show the destination.
5. Auto-start permission (Xiaomi/Oppo/Vivo/Honor) — separate, only on those OEMs.
6. "Fire a test reminder now" — proves it works on *this* device before the user trusts it.

`◆ DECISION — OEM guidance depth:` **Default for v1: detect manufacturer and show per-OEM text instructions + link to dontkillmyapp.com**, rather than building/maintaining a screenshot wizard for every brand. Upgrade to an in-app wizard later if support load justifies it.

## 2.15 Accessibility (Law 10 — launch gate)

TalkBack labels on every control · drug-name field announces suggestions · system font scaling respected (sp units, no truncation) · high-contrast support · no color-only warnings (icon + text) · touch targets ≥48dp · core flows (add med, mark taken, snooze, skip, view schedule) fully operable one-handed and under large fonts · simple, uncluttered primary actions (Taken / Snooze / Skip).

---

# PART III — TECHNICAL ARCHITECTURE

## 3.1 Assumed stack

`◆ DECISION (overridable):` Native Android, **Kotlin**, **Jetpack Compose** (good accessibility primitives), **Room** over SQLite, **AlarmManager** for exact alarms (NOT WorkManager — WorkManager is inexact), **WorkManager** for the non-time-critical CDN sync job. Rationale: native gives the most reliable control over alarms/Doze, which is the whole product.

## 3.2 Data sources & the bundle-plus-CDN pipeline

- **Drug identity:** RxNorm, trimmed to needed fields (ingredient, brand, RxCUI, synonyms), compiled to a compact bundled SQLite DB. Updated monthly via a build job you run, pushed to your CDN (Backblaze B2 behind Cloudflare). The app pulls the new DB from *your* CDN, never from NLM directly.
- **Side-effect/label text:** openFDA/DailyMed, fetched **per drug on add**, cached locally forever after. Never bundled wholesale.
- **Duplicate detection:** runs entirely on the local RxNorm ingredient data; no network.

## 3.3 Local schema (two logical stores)

- **Drug reference DB** (read-only, replaced by CDN updates): ingredients, products, RxCUIs, name synonyms, cached label text (with source + fetch timestamp).
- **User regimen DB** (read/write, never touched by CDN updates): medications, schedules (typed, including phases for tapers), dose instances + state + timestamps, refill records, settings. Dose history is **append/transition only — never overwrite** (Law 9 + 2.2). A dose-change (10mg→20mg) creates new schedule state; the old record persists.

## 3.4 Alarm scheduling architecture

- **Single-fire-and-reschedule.** Do NOT use setRepeating (inexact in Doze). Schedule the next dose instance as a single exact alarm; on fire, compute and schedule the next.
- **Permissions:** declare USE_EXACT_ALARM (medication reminders qualify as an alarm-type app under Play policy — see 4.1). Use full-screen-intent for the DUE alert (Android 14 restricts this to alarm/calling apps — justify in the same Play review).
- **Graceful degradation:** if exact-alarm capability is unavailable, fall back to setWindow rather than failing — degrade timing, never drop the reminder (Law 6).
- **Re-registration receivers:** recompute and reschedule on BOOT_COMPLETED, app update/reinstall, TIME_SET, TIMEZONE_CHANGED, and the daily DST/midnight rollover. **Time math correctness is a launch gate** — a dose firing at the wrong hour after a DST shift is a safety bug.
- **Time-zone travel UX** (the *prompt*) is v1.x; **correct DST/timezone handling** (the math) is v1 launch-critical. Keep these separate.

## 3.5 Database versioning, migration, rollback

- Both DBs carry a schema version. App refuses to load a DB whose schema is newer than it understands; it keeps the last good DB and retries (Law 9).
- CDN update is **atomic**: download → validate → swap. A failed/corrupt download leaves the prior DB in place and retries later (default: daily check, Wi-Fi-preferred).
- Every drug-DB release is reversible — keep the prior version locally until the new one validates. A bad drug-data push is a safety event and must roll back.

## 3.6 Crash reporting & analytics stance (Laws 4, 5)

- No third-party tracking/analytics/advertising SDKs.
- Reliability is observed **on-device** via the dashboard (2.13).
- Crash/error reporting is **opt-in, off by default**, PII-stripped, and — if implemented — self-hosted (e.g., self-hosted Sentry/Countly) so the data is yours. Disclosed plainly in onboarding and the privacy policy.

---

# PART IV — LAUNCH GATES (non-feature; must be done before release)

## 4.1 Google Play compliance
- Declare USE_EXACT_ALARM and full-screen-intent with a clean justification: *"This app uses alarm permissions only to deliver medication reminders the user creates."* Medication reminders fit the alarm-app use case, but it is a **review**, not an automatic pass — write the store-listing justification carefully.
- Store listing: prominent disclosure that the app does not provide medical advice. No prohibited claims in metadata ("prevents missed doses," "ensures safety" → not allowed). Recent target API level.
- Privacy policy linked in listing (mandatory).

## 4.2 Legal text (in-app, not just store)
- Terms of Service (disclaimers, limitation of liability).
- Privacy policy (describe local storage, CDN drug-DB fetches, any opt-in crash/sync data).
- Embedded medical disclaimer: *"This app is a reminder tool. It does not provide medical advice. Always consult your healthcare provider before changing your medication schedule."*
- Reference disclaimer wherever label/interaction text appears.
- (May adapt structure from open-source apps, but respect their licenses — do not copy GPL text into a closed app.)

## 4.3 Testing matrix (cannot ship without)
- Alarm fires after reboot · after app update · in overnight Doze · after timezone change · after DST shift · after manual clock change.
- On ≥3 OEM brands (e.g., Samsung, Pixel, Xiaomi, + one of Motorola/OnePlus).
- Missed first dose does NOT block the second.
- Snooze does not erase future doses; interval/taper recompute correctly; PRN max-warning fires.
- Backup restores cleanly; corrupt backup is rejected.
- Drug-DB update preserves the user regimen.
- Staged rollout: internal alpha → invite-only beta (recruit veterans/caregivers/elderly users) → open beta → production.

## 4.4 Accessibility audit
Full TalkBack pass on every core flow; large-font and high-contrast pass; one-handed operability check. Failures block release (Law 10).

---

# PART V — v2 QUARANTINE (deliberately NOT built in v1)

**Do not build these. Do not scaffold for these. If a slice "naturally" leads here, stop.**

- Encrypted cloud sync; multi-device.
- Caregiver dashboard, pairing, and the escalation ladder (and the caregiver-pays monetization tier).
- Full pairwise, severity-rated drug-drug interaction engine (licensed data).
- Internationalization / non-US drug databases.
- Wear OS companion; home-screen widgets.
- Custom alarm sounds, themes, icon packs; family/multi-profile.
- Pharmacy integration / refill ordering.
- Time-zone *travel prompt* UX (correct DST math stays in v1; the interactive "keep or shift?" prompt is here).
- Donations/tip-jar UI (mission-framed, in-app, dismissible) — build only after the core ships and earns trust.

The act of moving these here is what makes the v1 boundary real. v2 is a place so you can stop carrying it.

---

# PART VI — BUILD ORDER

Build one coherent slice at a time; verify each before the next. Each slice must obey Part I by construction.

1. **Foundations & schema.** Project setup, the two-DB structure, the trimmed RxNorm bundle, regimen schema (append-only history). No UI beyond scaffolding.
2. **Medication identity & entry.** Add/edit a med with strength/form confirmation; local RxNorm resolution; free-text fallback. Duplicate-ingredient warning (2.4).
3. **Schedules.** Fixed, days-of-week, interval, window, PRN, temporary, taper; pause/resume/end-date.
4. **Alarm engine & reliability.** Single-fire-and-reschedule, all re-registration receivers, full-screen DUE alert, setWindow fallback, the "test reminder now" path. Correct DST/timezone math. **This is the highest-risk slice — test hardest here.**
5. **Dose state machine.** DUE/TAKEN/SNOOZED/SKIPPED/MISSED with the 2.6 rules, the sacred dose channel, escalation, snooze + miss-window logic, independent dose instances.
6. **Onboarding & permission flow** (2.14) and the **reliability dashboard** (2.13).
7. **Refill tracking** (2.9) on its own channel.
8. **Drug reference** (2.10): per-drug openFDA fetch-and-cache with source/freshness; label-section reference text.
9. **Backup / restore / export** (2.12), including the post-wipe restore offer.
10. **Accessibility pass** across all flows (Law 10).
11. **Launch gates** (Part IV): Play compliance + justification, legal text, the full testing matrix, staged rollout.

Notes:
- Slices 4 and 5 are the core of the app and the core of the risk. Everything before them is setup; everything after assumes they're rock-solid.
- The CDN drug-DB build/update pipeline (3.2, 3.5) is a parallel track you can develop alongside slices 1–2 and harden by slice 8.
