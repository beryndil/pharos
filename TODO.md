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
