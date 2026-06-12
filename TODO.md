# Pharos — TODO / deferred log

Append in the moment work is deferred or a wall is hit. Read before answering
"what's next/left/missed."

## Dave-only walls (physical / account / payment — pipeline skips, never stops)

- [ ] Provision drug-DB CDN: Backblaze B2 bucket + Cloudflare in front. Pipeline builds
      against the documented contract + a local fixture until this exists. (Account +
      billing — Dave.)
- [ ] Generate/secure the release signing keystore out-of-tree; enroll **Play App
      Signing** at first Play upload. (Console + key custody — Dave.)
- [ ] Google Play Console: Data Safety + Health Apps declaration; USE_EXACT_ALARM,
      USE_FULL_SCREEN_INTENT, FGS-type declarations; store-listing medical-disclaimer
      copy review. (Console — Dave.)
- [ ] On-device test matrix (spec §4.3): reboot/Doze/DST/timezone across ≥3 OEM brands;
      TalkBack lived pass; full-screen-intent visuals. (Real hardware — Dave.)
- [ ] Ed25519 signing keypair for the drug-DB manifest: generate, embed public key in
      app, keep private key in the CDN build job. (Key custody — Dave/pipeline split.)

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
