# Pharos — TODO / deferred log

Append in the moment work is deferred or a wall is hit. Read before answering
"what's next/left/missed."

## Dave-only walls (physical / account / payment — pipeline skips, never stops)

- [ ] Provision drug-DB CDN: Backblaze B2 bucket + Cloudflare in front. Pipeline builds
      against the documented contract + a local fixture until this exists. (Account +
      billing — Dave.)
- [ ] **Replace CDN keypair and URL** (Slice 8): Follow the keypair replacement procedure in
      DECISIONS.md. Replace `ManifestVerifier.APP_PUBLIC_KEY_HEX` with the real public key,
      set `DrugDbUpdateWorker.CDN_BASE_URL` to the real CDN base URL, and store the private
      key in the CDN build job only. The current placeholder uses the RFC 8032 test vector key.
- [ ] **SPKI pinning for CDN domain** (Standards §6): Once the Cloudflare CDN domain is
      provisioned, add a `<domain-config>` entry to `network_security_config.xml` with the
      Cloudflare SPKI primary + backup pin and expiry date. Do NOT pin openFDA/NLM (gov rotation).
- [ ] **Generate and secure the release signing keystore out-of-tree** (DECISIONS.md S11-A1):
      Create `keystore.properties` at project root (gitignored) pointing to the real release
      keystore. Enroll **Play App Signing** at first Play upload. (Console + key custody — Dave.)
      Without `keystore.properties`, `assembleRelease` uses the debug keystore fallback — safe
      for local verification but the resulting APK must NOT be submitted to the Play Store.
- [ ] **Google Play Console** (spec §4.1, docs/play-listing.md):
      - Complete Data Safety + Health Apps declaration
      - Declare USE_EXACT_ALARM, USE_FULL_SCREEN_INTENT with the justification text in
        `docs/play-listing.md`
      - Review and paste store-listing copy from `docs/play-listing.md`
      - Link the Privacy Policy URL in the listing (host the policy text at a stable URL)
      - Submit and monitor Play review for USE_EXACT_ALARM and USE_FULL_SCREEN_INTENT
- [ ] **On-device test matrix** (spec §4.3, docs/testing-matrix.md):
      All [DEVICE] items in `docs/testing-matrix.md`. Minimum: Samsung Galaxy, Google Pixel,
      Xiaomi across all alarm-reliability scenarios plus TalkBack and full-screen-intent visual
      verification. (Real hardware — Dave.)
- [ ] Ed25519 signing keypair for the drug-DB manifest: generate, embed public key in
      app, keep private key in the CDN build job. (Key custody — Dave/pipeline split.)
      ↳ Now tracked more specifically in the "Replace CDN keypair and URL" item above.
- [ ] **Privacy policy hosting**: Host the Privacy Policy text (from the Legal screen in-app
      copy) at a stable public URL before Play Console submission. Required for apps declaring
      health-related permissions.

## Build-environment notes (for any future session)

- SDK at `~/.local/share/Android/Sdk`; **must** `source ./env.sh` (sets the redirected
  `ANDROID_USER_HOME`/`GRADLE_USER_HOME`/JDK21). `~/.android`, `~/.gradle`, `$HOME` root
  are read-only in the build sandbox.
- Use `./gradlew --no-daemon`; run gates in the foreground, timeout-wrapped.
- Emulator/instrumented tests may not run headless in the sandbox — not a pipeline
  blocker (see PIPELINE.md §Testing reality).

## Deferred during build

### Slice 1 — Schema & two-DB structure (2026-06-12)

- [ ] **StrongBox backing for Tink wrapping key** (DECISIONS.md A8): `StrongBoxUnavailableException`
      requires API 28 but minSdk=26. Address in a security hardening pass by version-gating
      the StrongBox path with `@RequiresApi(28)` and `Build.VERSION.SDK_INT >= 28` guard.
      Current TEE-backed Keystore key is production-ready.
- [ ] **SQLCipher integration test** (DECISIONS.md A9): Robolectric unit tests open the regimen DB
      with standard SQLite (no encryption). Add an instrumented test (on-device) that opens
      the DB with `SupportOpenHelperFactory` and a real passphrase. Part of Dave's device-test
      matrix (PIPELINE.md §Testing reality).
- [ ] **`/tmp/pharos-test-home` persistence** (DECISIONS.md A12): `/tmp` is cleared on reboot.
      Each new session must `mkdir -p /tmp/pharos-test-home` before running tests, OR the
      Gradle daemon will recreate it on the first `tasks.withType<Test>` run. The Robolectric
      SDK JAR download (~120 MB per SDK version) will re-run if `/tmp` was cleared.
      Mitigation: the `tasks.withType<Test>` config creates the dir at test time; no manual
      action needed per session.
- [ ] **Real RxNorm CDN bundle**: `drug_ref_fixture.db` contains 5 sample medications.
      Production bundle requires the full trimmed RxNorm dataset via the CDN pipeline
      (Slice 8 / parallel track). Dave task: provision Backblaze B2 + Cloudflare CDN.

### Slice 4 — Alarm engine & reliability (2026-06-12)

- [ ] **On-device alarm reliability matrix (Dave, spec §4.3):** reboot recovery
      (`BOOT_COMPLETED` re-arm after unlock), Doze/app-standby exact-alarm delivery,
      manual time/timezone change, DST spring-forward/fall-back, OEM battery-killer
      survival (Samsung Sleeping Apps / Xiaomi autostart / Huawei PowerGenie — see
      dontkillmyapp.com, DECISIONS.md D5). Robolectric proves the math + scheduling API;
      real-device delivery is Dave's pass and cannot run headless.
- [ ] **Full-screen-intent visuals (Dave):** verify `DueAlertActivity` takes over the
      screen on a real device (and over the lock screen), and that Android 14
      `canUseFullScreenIntent()` gating behaves. Confirm the Play Console full-screen-intent
      justification before submission.
- [ ] **Dedicated notification small icon:** `FullScreenDoseNotifier` currently reuses
      `ic_launcher_foreground` as the status-bar small icon. Add a monochrome notification
      icon (transparent, single-color) in the Slice 6 onboarding/dashboard or Slice 10 pass.
- [ ] **LOCKED_BOOT_COMPLETED not handled (DECISIONS.md S4-A2):** the regimen DB is
      credential-encrypted (PHI), unreadable before unlock, so re-registration happens at
      `BOOT_COMPLETED`. If a future slice makes any non-PHI alarm metadata direct-boot-aware,
      revisit handling `LOCKED_BOOT_COMPLETED` for earlier re-arm.
- [ ] **DUE alert actions are Slice 5:** `DueAlertActivity` ships the alert surface + a
      single "Open Pharos" action. Taken/Snooze/Skip buttons, escalation intensity, sacred-
      channel enforcement, and the D2/D3 miss-window/snooze rules plug into the
      `DoseActionHandler` seam in Slice 5.
- [ ] **Test-reminder UI entry point:** `AlarmCoordinator.scheduleTestReminder()` is wired
      through the real engine but has no on-screen button yet; the Slice 6 reliability
      dashboard surfaces the "Send a test reminder" affordance (Law 6).
- [ ] **WorkManager horizon top-up (robustness):** `onDailyRollover` extends the 90-day
      dose-instance horizon for active meds. If the device misses the daily rollover for a
      long stretch (off > horizon), the horizon could lapse. Consider a WorkManager periodic
      backstop (CDN-update channel only — never the dose reminder itself) in a later slice.

### Slice 3 — Schedules (2026-06-12)

- [ ] **TimePicker is24Hour** (ScheduleSection.kt `TimePickerDialog`): Hard-coded
      `is24Hour = false`. Should derive from `DateFormat.is24HourFormat(context)` for proper
      locale/user-preference handling. Fix in Slice 10 accessibility pass.
- [ ] **`Icons.Default.Add` as clock icon in `TimePickerField`**: Material3 doesn't expose
      an outlined clock via the standard icon set without `material-icons-extended`. Currently
      uses the `Add` icon as the picker trigger. Replace with a proper clock icon (e.g.
      `Icons.Outlined.AccessTime`) once extended icons are confirmed available, or in the
      accessibility pass.
- [ ] **INTERVAL anchor-time field**: When `INTERVAL` type is selected, the UI
      shows interval hours but does not expose a "start time" picker for the anchor
      (the first dose time). The anchor defaults to `times[0]` (08:00). If the user
      wants a non-08:00 anchor, they'd need to set it in the times list. Add an explicit
      "first dose at" field in a UX polish pass.
- [ ] **AddEditMedicationViewModelTest `saveRequested_validFields_savedSuccessfully`**:
      Test exercises the save path, which now calls `scheduleRepository.saveSchedule`.
      The test injects a real in-memory DB; confirm test still passes after Slice 3 lands
      (no mock needed, but verify). Will surface any FK violation if medication isn't
      inserted before schedule.

### Slice 2 — Medication identity & entry (2026-06-12)

- [ ] **FLAG_SECURE per-screen** (DECISIONS.md S2-A11): Currently applied globally in
      `MainActivity.onCreate()`. Refine to only screens rendering PHI during the Slice 10
      accessibility/security hardening pass, once onboarding screens (Slice 6) are added.
- [ ] **`searchDrugs` N+1 optimisation** (DECISIONS.md S2-A9 note): Ingredient name
      resolution in `searchDrugs` uses a single batch fetch (`getByRxcuiList`) — good for
      current fixture, will need profiling against full RxNorm dataset (Slice 8).
- [ ] **`Icons.Outlined.Medication`**: Requires `material-icons-extended`; already added
      as a dependency. If the exact icon name changes in a future BOM upgrade, update the
      import in `MedicationListScreen.kt`.
- [ ] **Edit-mode ingredient RxCUI re-resolution**: When editing a medication that was
      originally resolved from RxNorm, the ingredient RxCUIs are read from
      `MedicationEntity.ingredientsJson`. If the user changes the drug via the form,
      they re-enter the SEARCH→CONFIRM flow. Current implementation does not offer a
      "re-resolve this drug" path from the DETAILS step in edit mode — deferred to UX
      refinement (no user request yet).

### Slice 5 — Dose state machine (2026-06-12)

- [ ] **PRN-log UI entry point** (DECISIONS.md S5-A7): `DoseStateMachine.logPrnTaken` +
      `DoseRepository.logPrn` + the non-blocking daily-max warning (`prn_daily_max_warning`)
      are implemented and unit-tested, but no screen yet surfaces a "Log dose" affordance
      for PRN meds (PRN meds have no SCHEDULED instances, so they don't appear in the Today
      list). Add a PRN log button (Today or med detail) in Slice 6 onboarding/home polish,
      rendering `prn_daily_max_warning` when `PrnLogResult.exceedsMax`.
- [ ] **Multi-DUE notification fan-out** (DECISIONS.md S5-A8): two meds DUE at the same
      instant currently share one visual dose notification (single `NOTIFICATION_DOSE_DUE`
      slot); the LAST posted alert wins the tray. Dose *state* independence is preserved in
      the DB. Consider per-dose notification ids (keyed off dose-id hash) in a notifications
      polish pass so both DUE doses are individually actionable from the shade.
- [ ] **Per-dose alarm request-code collision** (DECISIONS.md S5-A3): miss/re-alert
      PendingIntent request codes derive from `doseId.hashCode()`. UUID hash collisions in a
      realistic dose set are astronomically unlikely, but a per-dose monotonic int column on
      `dose_instances` would make it provably collision-free — Slice 11 hardening candidate.
- [ ] **Today view = single-day window**: `DoseRepository.observeTodayDoses()` bounds at
      start-of-tomorrow in the current zone and recomputes only on screen re-entry. A live
      midnight rollover while the screen is open won't refresh the bound until recomposition.
      Acceptable for v1; revisit if a persistent home dashboard lands (Slice 6).
- [ ] **DoseHistory cause detail**: the history screen renders the destination state + a
      timestamp. The `DoseTransitionCause` (alarm/user/snooze-elapsed/miss-window) is stored
      but not yet surfaced. Add a secondary line in the accessibility/polish pass if useful.

### Slice 9 — Backup / restore / export (2026-06-12)

- [ ] **PDF export uses PdfDocument canvas — no text wrapping**: Long medication names or
      schedule descriptions may overflow the page width. For v1 the layout is sufficient;
      add word-wrap / multi-line text in a PDF polish pass if needed.
- [ ] **Backup destination shown in success toast**: currently only "Backup saved." is shown;
      the actual URI is not surfaced. Consider adding a "Share" action to the snackbar (e.g.
      `Intent.ACTION_SEND`) in a future polish pass so users can immediately email themselves
      the file.
- [ ] **Restore does not re-arm alarms**: After a restore, `AlarmCoordinator.reScheduleAll()`
      should be called to schedule dose alarms for the restored data. Wire this in
      `BackupRepository.restore` (post-import step) or have `BackupViewModel` trigger it via
      a callback. Deferred to avoid the circular dep between backup/ and alarm/ packages —
      clean seam is a `BackupRepository` constructor parameter or a post-restore callback.
      File under Slice 11 hardening.
- [ ] **Passphrase field does not prevent screenshots**: `FLAG_SECURE` is global so the
      passphrase dialog is covered. If per-screen FLAG_SECURE is later enabled (S6-A1 TODO),
      ensure the passphrase dialog screen is added to the secure set.
- [ ] **Access token revocation on restore** (nice-to-have): after a replace-all restore,
      any pending WorkManager jobs (low-supply check, CDN update) were scheduled for the old
      data. They will re-enqueue harmlessly on next app start but could fire once on stale
      medication IDs. Cancel and re-enqueue WorkManager on restore in Slice 11 hardening.

### Slice 8 — Drug reference + CDN pipeline (2026-06-12)

- [ ] **CDN_BASE_URL** (DECISIONS.md S8-A7): `DrugDbUpdateWorker.CDN_BASE_URL` is blank;
      the worker skips gracefully. Dave fills in the real Backblaze B2 + Cloudflare URL.
- [ ] **Replace APP_PUBLIC_KEY_HEX** (DECISIONS.md S8-A3): Current fixture key is the RFC 8032
      test vector. Dave generates a real keypair per the DECISIONS.md procedure and replaces.
- [ ] **SPKI pinning** (Standards §6): Add `<domain-config>` with Cloudflare SPKI pins to
      `network_security_config.xml` once the CDN domain is known. Template is documented in
      DECISIONS.md.
- [ ] **openFDA label refresh**: Cached labels are forever (spec §2.10). A future v1.x
      enhancement could offer a "refresh" button on the reference screen for staleness.
- [ ] **Drug reference for free-text meds**: The reference screen correctly shows a plain
      message for free-text meds. If a user later re-resolves a med from the search
      (edit mode), the label will be fetched on next AddEditMedication save.

### Slice 7 — Refill tracking (2026-06-12)

- [ ] **TAPER schedule doses/day** (DECISIONS.md S7-A3): `computeDosesPerDay` returns null for
      TAPER schedules because the weighted average doesn't account for which phase the user is
      currently in. Implement a phase-position-aware calculation if TAPER low-supply alerts
      prove important to users.
- [ ] **Refill channel in WorkManager tests**: `LowSupplyCheckWorker` is tested indirectly via
      `RefillRepository`. A direct WorkManager integration test using `WorkManagerTestInitHelper`
      would verify end-to-end worker execution — deferred (requires Android test runner).
- [ ] **Configurable low-supply threshold**: Threshold is hardcoded at 7 days. Add a per-med
      override in a future settings pass if users request it.
- [ ] **Refill-by date picker in UI**: `RefillDialogState.SetRefillByDate` dialog skeleton is
      present in the sealed class but the UI dialog case (`SetRefillByDate ->`) only returns
      `Unit`. Wire a `DatePickerDialog` to `RefillEvent.ConfirmSetRefillByDate` in the
      Slice 10 accessibility/polish pass.
- [ ] **Pharmacy phone stored on medication vs refill record**: `RefillRecordEntity.pharmacyPhone`
      stores phone per-refill event; `MedicationEntity.pharmacy` stores a free-text pharmacy
      field. The RefillRepository merges them (refill record wins). Consider unifying in a
      future schema version.

### Slice 6 — Onboarding + reliability dashboard (2026-06-12)

- [ ] **FLAG_SECURE per-screen** (DECISIONS.md S6-A1): Global `FLAG_SECURE` covers onboarding
      and the reliability dashboard even though they carry no PHI. Refine to per-screen in the
      Slice 10 security hardening pass using a `DisposableEffect` composable wrapper.
- [ ] **Reliability dashboard on-resume permission refresh** (DECISIONS.md S6-A2): Permissions
      are snapshotted once at ViewModel creation. If the user grants a permission while the
      dashboard is backgrounded, the display won't update until they navigate away and back.
      Add a `LaunchedEffect(lifecycleState)` refresh trigger in a future polish pass.
- [ ] **Dedicated notification small icon** (carried from Slice 4): `FullScreenDoseNotifier`
      reuses `ic_launcher_foreground`. Add a monochrome notification icon in the Slice 10
      accessibility pass.
- [ ] **PRN log entry point** (carried from Slice 5): PRN meds have no Today-screen row.
      Surface a "Log dose" affordance — consider a FAB or a per-med action on the medications
      list. Render `prn_daily_max_warning` when `PrnLogResult.exceedsMax`.
- [ ] **Boot-receiver display — trigger name readability**: The dashboard shows the raw
      Android action string (e.g. `android.intent.action.BOOT_COMPLETED`). A future polish
      pass could shorten it to a human-readable label.
- [ ] **Drug DB version / last-updated**: These keys (`drugref.version`, `drugref.updated_at`)
      are written by the Slice 8 CDN pipeline. Dashboard shows "Bundled (local)" / "Not yet
      updated from CDN" until Slice 8 lands.

### Slice 11 — Launch gates + release (2026-06-12)

- [ ] **Restore → re-arm alarms** (DECISIONS.md S9 note, carried forward): After a restore,
      `AlarmCoordinator.reScheduleAll()` should be called to arm dose alarms for the imported
      data. Currently the restore completes but alarms for restored medications are not
      re-registered until the next `BOOT_COMPLETED` or app restart. Fix: add a
      `BackupRepository` callback or pass `AlarmCoordinator` as a constructor parameter.
      The dependency cycle (backup/ → alarm/) must be broken cleanly (e.g. via a
      post-restore interface or by calling the coordinator in the ViewModel post-restore event).
- [ ] **WorkManager jobs after restore** (Slice 9 TODO): Cancel and re-enqueue
      `LowSupplyCheckWorker` and `DrugDbUpdateWorker` after a replace-all restore so stale
      job state for the old medication IDs doesn't linger.
- [ ] **Privacy policy URL**: Once the policy is hosted at a stable URL, add a clickable link
      to `LegalScreen.kt` (currently plain text — no link since no URL exists yet).
- [ ] **Legal screen version string**: Update `legal_privacy_version_note` and
      `legal_terms_version_note` in `strings.xml` whenever the legal text changes in a future
      version. The version string is intentional (readers need to know which version they agreed to).
- [ ] **Per-dose alarm request-code collision** (carried from Slice 5): see TODO item in
      Slice 5 section above.

## Slice 10 — Accessibility (on-device pass required, Dave)

- [ ] **TalkBack lived experience pass** (Law 10, §4.4): Navigate every core flow — add med,
      mark taken/snooze/skip, view schedule, refill, backup/restore, onboarding, reliability
      dashboard, drug reference — end to end with TalkBack enabled on a real device. Confirm:
      (1) every interactive element is announced meaningfully; (2) no two controls are
      indistinguishable in TalkBack; (3) focus order is logical on each screen;
      (4) dose action announcements include the medication name.
      Automated Robolectric tests (AccessibilitySemanticsTest) cover node presence; this
      covers lived navigation quality that can only be assessed on device.

- [ ] **Large font scale visual pass** (Law 10, Standards §8): Set system font scale to 2.0×
      and walk every screen. Confirm no text truncation, no button overlap, no layout breakage.
      The `heightIn(min = 48.dp)` and `fillMaxWidth` patterns in the code handle most cases
      but must be confirmed at actual max-font rendering.

- [ ] **High-contrast / dark-mode visual pass**: Confirm all status icons + text remain
      legible against their backgrounds in both light and dark themes (WCAG AA contrast ≥ 4.5:1
      for normal text, ≥ 3:1 for large text / UI components). Material 3 dynamic color handles
      most of this, but verify dose state labels (DUE, MISSED, TAKEN) and warning banners.

- [ ] **Monochrome notification small icon** (carried from Slice 4): `FullScreenDoseNotifier`
      uses `ic_launcher_foreground` as the notification small icon. Add a dedicated
      monochrome/transparent-background icon (24×24dp, white on transparent). Required by
      Android notification styling and for correct display in the status bar.

### Post-v1.0.0 — on-device launch crash fixed (2026-06-12)

- [x] **SQLCipher native lib not loaded → crash on first launch** (v1.0.1 fix). The
      `net.zetetic:sqlcipher-android` artifact has no `loadLibs()` helper and does not
      self-load; the app must call `System.loadLibrary("sqlcipher")` before opening any
      encrypted DB. Added to `PharosApplication.onCreate`. Robolectric tests use plain
      SQLite (DECISIONS.md A9) so they could not catch this — it surfaced only by booting
      the release on an emulator. **Lesson: a launch smoke test on a real device/emulator
      is mandatory before tagging a release.** The on-device test matrix (§4.3) now must
      include a cold-launch + encrypted-DB-open check as gate zero.

## A1 — Critical Alerts (device-matrix Section C, 2026-06-12)

These items require real hardware and cannot be automated in the pipeline (spec §8 — device matrix). Dave handles these before promoting any tester track.

- [ ] **Section C: Critical alert sounds through ringer-silent/vibrate mode** — Verify on Samsung Galaxy, Google Pixel, and Xiaomi at minimum. Put device in silent/vibrate, fire a critical test reminder from the reliability dashboard, confirm it rings at alarm volume.
- [ ] **Section C: Critical alert sounds through Do Not Disturb** — Enable DND (all notifications blocked), fire the critical test reminder, confirm it breaks through when DND policy access is granted.
- [ ] **Section C: Critical alert sounds through both silent AND DND simultaneously** — Compound test. Confirm both blocking mechanisms are defeated.
- [ ] **Section C: DND-access-denied path visible** — Revoke DND policy access, open the reliability dashboard, confirm dndAccess shows RISKY with the correct "Critical override is OFF" message. Fire the critical test reminder, confirm it still delivers (on standard channel, not silenced by OS before grant).
- [ ] **Section C: Verified overnight in Doze** — Leave device idle for ≥3 hours (screen off, stationary), confirm a critical alarm fires at the scheduled time.
- [ ] **Section C: OEM-specific verification** — Samsung (One UI), Xiaomi (MIUI), and Motorola/OnePlus if available. DND bypass behavior differs by OEM.
- [ ] **Section C: Non-critical meds do not bypass DND** — Confirm a non-critical med reminder is silenced by DND when bypass is NOT granted to that channel.
- [ ] **Section C: Channel-not-modifiable caveat** — Document what happens if the user manually modifies the critical channel via system settings (Android then controls bypass state, not the app). Surface this limitation in the reliability dashboard help text if needed.
- [ ] **Section C: ACCESS_NOTIFICATION_POLICY Play justification** — File the `ACCESS_NOTIFICATION_POLICY` declaration in the Play Console using the text in `docs/play-listing.md`. Google may review this permission; have the user-flow screenshots (lazy DND prompt on first critical med) ready.
- [ ] **Section C: USE_FULL_SCREEN_INTENT on Android 14+ verification** — Confirm `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` opens correctly and grants permission. Dashboard shows correct state after grant/revoke.

## A3 — Polish / quality (executor ses_e0f40ec5, 2026-06-12)

All A3 items are implemented. Device-only verification deferred below.

### Section C: A3 device-only verification

- [ ] **Section C: 12/24h time picker** — Set device to 24-hour time in Display settings, open any schedule screen, confirm the TimePicker shows 24-hour dial. Set back to 12h, confirm AM/PM is shown.
- [ ] **Section C: Monochrome notification icon** — Fire a test reminder from the reliability dashboard. Confirm the notification's small icon in the status bar is the lighthouse silhouette (not a "+" shape). Check tray on both light and dark system UI. Verify color tinting works (status bar vs notification shade).
- [ ] **Section C: Multi-due notifications** — Schedule two medications with the same due time. When both fire, confirm they appear as SEPARATE notification tray entries (not merged into one). Verify that acting on one (Taken) does not dismiss the other.
- [ ] **Section C: Dashboard permission refresh on resume** — Open reliability dashboard; all items show OK. Go to system Settings → Apps → Pharos → Permissions, revoke exact-alarm permission. Use system back to return to the reliability dashboard. Confirm the "Exact alarm" row immediately updates to RISKY (no screen re-navigation required).
- [ ] **Section C: Per-screen FLAG_SECURE** — Navigate to: (a) Today screen, (b) Medication list, (c) Dose history, (d) Add/edit medication, (e) Backup screen. On each, attempt to take a screenshot — confirm it is blocked/blank. Then navigate to Legal, attempt screenshot — confirm it succeeds. Confirm same for Onboarding.
- [ ] **Section C: StrongBox wrapping key** — On a device with StrongBox (Pixel 3+ or Samsung with Titan M): fresh install, open the app, confirm no crash. Use `adb shell keystore2_client list` or Android Keystore API check to verify `pharos_master_key` is StrongBox-backed. On a device without StrongBox (emulator): same fresh install, confirm TEE fallback works and no crash.
- [ ] **Section C: PDF word-wrap** — Export medication list PDF with a medication that has a long schedule description (e.g. taper with multiple phases). Open the PDF, confirm all text is visible and wraps within margins — no clipping at right edge.
- [ ] **Section C: Share backup snackbar action** — Create a backup from the Backup screen. When the "Backup saved. / Share" snackbar appears, tap Share. Confirm the system share sheet opens with the backup file. Confirm sharing via Gmail/Drive works.
- [ ] **Section C: Dose history cause line** — Open dose history for a medication that has been through DUE → TAKEN. Confirm the third text line per row shows the cause ("Confirmed by you", "Alarm fired", etc.) in a smaller gray font.
- [ ] **Section C: Interval anchor time field** — Create an interval schedule with SCHEDULE_ANCHORED anchor type. Confirm a "First dose time" time picker appears. Set it to 10:00. Save. Edit the medication again and confirm the anchor time is 10:00. Verify the engine schedules doses at 10:00, 18:00 (for 8h interval), etc.
- [ ] **Section C: Boot trigger human-readable label** — Reboot the device. Open the reliability dashboard. Confirm the "Boot receiver" row shows "Boot at HH:MM · date" (not the raw "android.intent.action.BOOT_COMPLETED" string).

## A3 update (2026-06-12): launcher icon done early
- The "Teal launcher + splash icon" A3 polish item is SUPERSEDED — Dave supplied the lighthouse
  artwork and it's now the launcher icon in all locations (see DECISIONS.md ICON-1..4, commit below).
- Monochrome notification icon: now shipped as `ic_notification.xml` (A3 complete).

## G1 — Per-medication configurable miss window (2026-06-12, executor ses_b0791e32)

### Section C: G1 device-only verification

- [ ] **Section C: Miss window field UX on device** — Open Add Medication. In the Details step, locate "Reminder grace period (minutes)" near the Critical reminder toggle. Confirm the numeric keyboard appears on focus, the default value is 60, and the helper text reads "How long after a dose is due before it is marked missed." Attempt to enter 4 → confirm error message. Enter 90 → confirm save succeeds.
- [ ] **Section C: Custom miss window fires correctly** — Create a medication with missWindowMinutes=5. Fire a test dose alarm from the reliability dashboard (or wait for a real dose). After 5 minutes without action, confirm the dose transitions to MISSED in the dose history. Confirm a 60-min-window med on the same device does NOT miss at 5 min.
- [ ] **Section C: Fallback path for absent medication row** — The `computeMissClose` fallback to `GRACE_MS` when `medicationDao.getById()` returns null is a defensive code path that cannot be triggered through normal app operation (medications are never physically deleted). No device test needed; the unit-test gap is documented here (DECISIONS.md G1-E).
