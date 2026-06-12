# Pharos — Decisions Log

Records the `◆ DECISION` calls from BUILD_SPEC.md (resolved by Claude under Dave's
"decide on the best answers" directive, 2026-06-12) and any architectural choices made
during the build. Dave may override any of these by editing this file before the
relevant slice is built.

## Spec `◆ DECISION` resolutions

| ID | Decision | Resolution | Rationale |
|----|----------|-----------|-----------|
| D1 | Interval scheduling anchor (§2.5.3) | **Schedule-anchored** by default; per-med opt-in toggle "interval from last dose taken". Build both. | Spec default; predictable, DST-safe; the opt-in covers PRN-ish interval meds. |
| D2 | Miss window (§2.6) | **60 min after due OR next scheduled dose of the same med, whichever first**; windowed dose → window end. | Spec default; bounds DUE state, preserves dose independence. |
| D3 | Snooze rules (§2.6) | **15-min interval, repeatable, never past the miss window** (snoozed → MISSED when window closes). | Spec default; prevents indefinite snooze of a stale dose. |
| D4 | Backup format (§2.12) | **Encrypted JSON**, schema-versioned, AES-256-GCM, Argon2id KDF, user passphrase. | Portable, inspectable, validatable; see Standards §6. |
| D5 | OEM guidance depth (§2.14) | **Detect manufacturer + per-OEM text instructions + dontkillmyapp.com link.** No screenshot wizard in v1. | Spec default; low maintenance, covers the killers. |

## Architecture decisions made during build

| ID | Decision | Rationale |
|----|----------|-----------|
| A1 | Manual constructor DI via `AppContainer` on `PharosApplication`; no Hilt/Dagger in v1. | Keeps the graph inspectable; fewer build-plugin failure modes for the autonomous pipeline. |
| A2 | Toolchain: AGP 8.13.2, Kotlin 2.0.21, Compose BOM 2024.09.03, Room 2.6.1, KSP 2.0.21-1.0.28, Gradle 8.14.4, **JDK 21** (JDK 26 breaks AGP), compileSdk/targetSdk 35, minSdk 26. | Matches our last working Android build (Spyglass); proven on this host. |
| A3 | Two Room DBs as separate `RoomDatabase` classes (`RegimenDatabase`, `DrugRefDatabase`); regimen DB SQLCipher-encrypted, drug-ref DB plaintext (public data, integrity-checked on download). | Spec §3.3; encrypt PHI, not public reference data. |
| A4 | `core/time/DoseClock.kt` is the single source of all scheduling math. | One DST-correct path, fully unit-tested. |
| A5 | SDK lives at `~/.local/share/Android/Sdk`; `ANDROID_USER_HOME`, `GRADLE_USER_HOME` redirected to `~/.local/share/` (the real `~/.android`, `~/.gradle`, `$HOME` root are read-only in the build sandbox). Captured in `env.sh`. | Sandbox environment fact; without it sdkmanager/gradle fail with EROFS. |

## Slice 1 decisions (Schema & two-DB structure)

| ID | Decision | Rationale |
|----|----------|-----------|
| A6 | `net.zetetic:sqlcipher-android:4.5.6` used instead of spec's "≥4.13.0". The `sqlcipher-android` artifact versioning starts at 4.5.x; 4.13.0 does not exist. Using latest stable 4.5.6 which satisfies the 16KB page-size requirement. | Spec artifact versioning discrepancy; using latest stable. |
| A7 | `com.google.crypto.tink:tink-android:1.14.0` for Tink AndroidKeysetManager. | Most recent stable Tink 1.x with stable AndroidKeysetManager API. |
| A8 | StrongBox backing for the Tink wrapping key deferred to a security hardening pass. `StrongBoxUnavailableException` is API 28+ but minSdk=26; referencing it in a catch clause fails lint's NewApi check. Current implementation uses Android Keystore TEE (available on all API 26+ devices), which is production-ready. | Lint compliance + minSdk constraint. |
| A9 | Robolectric unit tests use `Room.inMemoryDatabaseBuilder` WITHOUT SQLCipher `SupportOpenHelperFactory`. SQLCipher's native .so is unavailable in the JVM test environment. Room schema, entities, and DAO logic are fully tested; the encryption layer is tested at integration level (on-device). | JVM test environment constraint per PIPELINE.md §Testing reality. |
| A10 | `@Database` class and factory/guard logic split into separate files (`RegimenDatabase.kt` + `RegimenDatabaseFactory.kt`, `DrugRefDatabase.kt` + `DrugRefDatabaseFactory.kt`). KSP can't resolve SQLCipher types referenced inside the `@Database`-annotated class. Keeping the `@Database` class minimal (DAO accessors only) resolves the issue. | KSP annotation processing constraint. |
| A11 | Room schema JSON files added to `debug` Android sourceSet assets (not `test` or `release`). `MigrationTestHelper` uses `instrumentation.context.assets` which maps to the merged debug APK assets, not the unit-test APK assets. Adding to `debug` sourceSet is the correct hook. Schema files are excluded from release builds. | MigrationTestHelper asset resolution in Robolectric. |
| A12 | Robolectric `user.home` redirected to `/tmp/pharos-test-home` via `tasks.withType<Test>().configureEach { systemProperty("user.home", ...) }`. The build sandbox mounts the real `$HOME` root read-only, so Robolectric's `MavenDependencyResolver` cannot create `~/.robolectric-download-lock`. | Sandbox environment constraint (learned A5 pattern). |
| A13 | `DrugRefDatabase` fixture seeded via `BundledDrugRefLoader` + `RoomDatabase.Callback.onCreate` instead of `Room.createFromAsset()`. `createFromAsset()` requires a Room-managed SQLite file (with `room_master_table` identity hash), but the hash isn't known until KSP runs. Using a raw SQLite asset (created via `sqlite3` CLI) read by the loader avoids this coupling. | Build-time dependency constraint. |

## Slice 3 decisions (Schedules)

| ID | Decision | Rationale |
|----|----------|-----------|
| S3-A1 | `ScheduleEngine` is a pure `object` with no I/O or Android deps — safe to unit-test on the JVM without Robolectric. | Keeps the generation logic testable and portable. |
| S3-A2 | `ScheduleSection` is an inline composable within the Details step (no separate nav destination). | Schedule config is tightly coupled to med entry; a separate screen adds nav complexity without UX benefit. |
| S3-A3 | For `INTERVAL` schedules, `scheduledTimesJson` stores a 1-element array with the anchor time (the time of the first dose on the start date). | Reuses the existing column rather than adding a new one; documented in code. |
| S3-A4 | `LAST_TAKEN` interval `generateInstances` returns at most 1 instance (the first after `from`). Alarm engine (Slice 4) drives subsequent generation after each dose taken. | LAST_TAKEN semantics require the next instance to depend on the *actual* taken time, which is unknowable at pre-generation time. |
| S3-A5 | `windowEndEpochMs` for FIXED_DAILY/INTERVAL instances = `min(dueMs + 60 min, nextDueMs)`. Slice 5 state machine applies D2 rules at runtime; engine sets a reasonable default. | Correct D2 cross-instance math requires runtime state; the 60-min default is a safe lower bound. |
| S3-A6 | Pause/Resume/End mutate `MedicationEntity.status` via `MedicationRepository`. Resume additionally calls `ScheduleRepository.generateInstancesForMed` for 90 days. | Status is a med-level property; generation is schedule-level. Separation keeps the repositories focused. |
| S3-A7 | Schedule save is NOT wrapped in a cross-DAO transaction. Med entity is saved first, then schedule + instances. | Room doesn't support cross-repository `@Transaction`; acceptable for v1 where the failure window is narrow. Log in TODO.md for Slice 11 hardening if needed. |
| S3-A8 | Test assertion `intervalScheduleAnchoredEvery8h` corrected from 6 to 5 instances. With anchor=08:00 and to=midnight+2days (exclusive), the midnight-of-day-3 instance falls exactly on `to` and is excluded. | The [from, to) half-open convention is the correct and consistent API; the original test comment ("2 days × 3 doses/day") was wrong about the count. |

## Slice 4 decisions (Alarm engine & reliability)

| ID | Decision | Rationale |
|----|----------|-----------|
| S4-A1 | `AlarmScheduler` interface + `AndroidAlarmScheduler` impl over `AlarmManager`; `AlarmCoordinator` owns the single-fire-and-reschedule loop. Both unit-tested with Robolectric `ShadowAlarmManager` asserting exact trigger times. | Standards §3/§10; keeps the risk-core logic testable without a device. |
| S4-A2 | Re-registration runs at `BOOT_COMPLETED` (post-unlock), **not** `LOCKED_BOOT_COMPLETED`. | The regimen DB is credential-encrypted PHI (DECISIONS.md A3) and is unreadable before the user unlocks; a direct-boot receiver could not read it to recompute alarms. Logged in TODO.md. |
| S4-A3 | Dose/test alarms use `setAlarmClock()` (exact, Doze-exempt, status-bar visible); the daily rollover uses `setExactAndAllowWhileIdle()` (maintenance — no status-bar alarm-clock affordance). Both fall back to `setWindow()` (10-min window) when `canScheduleExactAlarms()` is false — never drop the reminder (Law 6, §3.4). | Spec §3.4 graceful degradation; setAlarmClock is the spec-mandated primary. |
| S4-A4 | Single pending dose alarm (one `PendingIntent` slot, stable request code). `getEarliestScheduled()` (new `DoseInstanceDao` query — no schema change) drives it; on fire the dose is marked `DUE` so it drops out, then the next is scheduled. A past-due trigger (device was off) is scheduled at its past time so AlarmManager fires it immediately (reboot recovery, no dropped dose). | Spec §3.4 single-fire-and-reschedule; never `setRepeating`. |
| S4-A5 | The alarm-fire transition marks the dose `DUE` only. The full `DUE→TAKEN/SNOOZED/SKIPPED/MISSED` machine, escalation, sacred-channel enforcement, and D2/D3 miss-window/snooze rules are Slice 5, plugging into the `DoseActionHandler` seam. | Slice boundary per build order; Slice 4 is firing + alert plumbing. |
| S4-A6 | Reliability events persisted via the existing key-value `SettingDao` (`reliability.*` keys), not a new entity. | Avoids a Room version bump/migration; Slice 6 dashboard reads these keys. |
| S4-A7 | Test reminders schedule a `TEST`-kind alarm through the SAME scheduler and fire a transient notification — they do **not** create a `DoseInstance` row. | Law 6 (every alarm testable) without polluting append-only dose history. |
| S4-A8 | Timezone change re-arms from the stored absolute instant (epoch-ms), not a wall-clock recompute. Time-zone *travel* re-anchoring UX is v1.x (§3.4); v1 fires doses at the instant matching the schedule's original-zone wall clock. | Stored instants are DST-correct via `DoseClock`; re-reading them is the correct v1 math. |

## Slice 5 decisions (Dose state machine)

| ID | Decision | Rationale |
|----|----------|-----------|
| S5-A1 | Legal transitions = the spec §2.6 diagram PLUS `SNOOZED→TAKEN` and `SNOOZED→SKIPPED`. Centralized in `dose/DoseTransition.kt`; `DoseStateMachine` checks it and throws `IllegalDoseTransitionException` on any violation. | A snoozed dose stays user-actionable from the today view; recording the real outcome the user chose is more faithful than forcing a wait. No advice implied (Law 3) — the user initiates, the app records. |
| S5-A2 | Append/transition-only history is a **new `dose_transitions` table** (Room v1→v2 migration, additive) holding one immutable row per transition. The `dose_instances` row remains the *current-state projection* (fast alarm-engine queries) and is updated via the existing narrow `markX` DAO methods. | Satisfies "a transition adds a row, prior state row intact" (Law 9) with full tamper-evident history, while keeping `getEarliestScheduled()` and friends O(1). The DAO exposes INSERT only on transitions. |
| S5-A3 | Timed transitions (D2 miss-window deadline, D3 snooze-wake / escalation re-alert) are scheduled via a **new additive `DoseTransitionScheduler`** over `AlarmManager` with **per-dose request codes** (derived from the dose-id hash; distinct action per purpose). The Slice 4 single-fire dose alarm is untouched. | Two meds can be DUE at once (e.g. both at 08:00); each needs its own independent miss/re-alert alarm (Law 3 independence). Keeps the alarm engine un-rewritten; new actions route through `AlarmReceiver`. Fires app-closed (`setExactAndAllowWhileIdle`, windowed fallback). |
| S5-A4 | `AlarmCoordinator` gains an optional `DoseDueListener` (NoOp default) it calls after marking a dose DUE; `DoseStateMachine` implements it to arm the miss/escalation timers. The Take/Snooze/Skip seam stays `DoseActionHandler`. | Minimal seam-fill, not a rewrite. Existing Slice 4 tests (no listener arg) keep compiling against the default. |
| S5-A5 | Escalation level is **stateless**: `((now − due) / 5 min)` capped at 3, computed at each re-alert. Re-alert cadence 5 min; the dose channel posts with `setOnlyAlertOnce(false)` so each re-alert re-sounds. No new column. | Avoids a schema field for escalation; rising intensity is derived from elapsed time. |
| S5-A6 | D3 snooze caps the wake at `min(at+15min, missClose)`. A snooze whose 15-min target would land at/after the miss window is recorded as SNOOZED with the wake at the window close, where `onReAlert` converts it to MISSED. | Implements "snooze can never push a dose past its miss window" without a special pre-check branch. |
| S5-A7 | PRN logs are `DoseInstanceEntity` rows inserted directly in state `TAKEN` (no SCHEDULED/MISSED). Daily-max is counted via `countTakenSince(med, startOfDay)`; the warning is returned as data (`PrnLogResult`) and is **non-blocking** — the dose is always logged. | Spec §2.7; Law 3 (warn, never forbid). The PRN-log UI entry point (a "log dose" affordance for PRN meds) is deferred — see TODO. |
| S5-A8 | Single dose-notification slot (`NOTIFICATION_DOSE_DUE`); `cancelDoseAlert` clears it. Two simultaneously-DUE doses share one visual notification. | Dose **state** independence is preserved in the DB regardless (each instance transitions on its own row); only the visual collapse is shared. Multi-DUE notification fan-out deferred — see TODO. |

## Open decisions deferred to their slice (not blockers)

- App package id confirmed `com.beryndil.pharos`.
- Drug-DB CDN host/bucket names: placeholder until Dave provisions Backblaze B2 +
  Cloudflare. The pipeline (Slice 8 / parallel track) builds against a documented
  contract and a local fixture; **provisioning the real bucket and DNS is a Dave task**
  (account creation + payment) — logged in TODO.md, not a code blocker.
- Release signing keystore: generated and stored out-of-tree at first release-build
  need; Play App Signing enrollment is a Dave/console task.

## Slice 6 decisions (Onboarding + reliability dashboard)

| ID | Decision | Rationale |
|----|----------|-----------|
| S6-A1 | `FLAG_SECURE` kept global (set in `MainActivity.onCreate`). Onboarding and reliability dashboard screens carry no PHI but are still protected by the flag. | Conservative security stance. The existing A2-A11 TODO note flags this for per-screen refinement in a security-hardening pass. Adding/removing the flag per-navigation-destination requires a `DisposableEffect` in every composable — deferred to that pass. |
| S6-A2 | Reliability dashboard permission checks snapshotted once at ViewModel construction; not re-checked while the screen is visible. | Compose Navigation recreates the ViewModel each time the user navigates to the dashboard, so permissions are always fresh on arrival. Mid-session permission changes require a navigate-away-and-back to refresh — acceptable for v1. TODO.md logged for a future on-resume refresh. |
| S6-A3 | `OnboardingViewModel` and `ReliabilityDashboardViewModel` inject platform dependencies (OEM name, SDK version, permission checks) as lambdas/primitive values rather than via `Context`. | Keeps both VMs fully testable on the JVM with `Dispatchers.setMain` only — no Robolectric required. 18 onboarding + 29 dashboard tests pass without emulator. |
| S6-A4 | `OnboardingRepository` is `open` with `open` suspend methods to allow test-only subclassing without a live database. | Avoids an interface-extraction refactor for a class used only in the onboarding path. The `open` modifier has no runtime cost and keeps the class hierarchy flat. |
| S6-A5 | Auto-start item in the reliability dashboard always shows RISKY for Xiaomi/Oppo/vivo/Honor — there is no API to check if auto-start is actually enabled. Fix action links to dontkillmyapp.com (D5). For non-OEM-killer devices, shows OK. | There is no programmatic way to verify auto-start state on these OEMs. Showing RISKY with a fix link is more useful than silently showing OK when the alarm may still be killed. |

## Slice 2 decisions (Medication identity & entry)

| ID | Decision | Rationale |
|----|----------|-----------|
| S2-A1 | `androidx.navigation:navigation-compose` 2.8.4 | Compatible with Compose BOM 2024.09.03 (Compose 1.7.x); navigation 2.8.x is the first stable line supporting Compose 1.7+. |
| S2-A2 | Add/Edit flow is a **single screen** with internal step state (SEARCH → CONFIRM → DETAILS) rather than three separate nav destinations. | Shared ViewModel state is simpler; back navigation through steps works naturally with `StepBack` events before popping the nav stack. |
| S2-A3 | `MedicationRepository` lives in `data/medication/` (separate from `data/regimen/` and `data/drugref/`). | It bridges both databases and belongs at a cross-cutting data layer; avoids putting business logic in the feature package. |
| S2-A4 | Drug search returns `DrugSearchResult` (product-level: name + strength + form + ingredients) rather than raw ingredient-level results. | Users identify meds by product (e.g., "Tylenol 500 mg Tablet"), not by ingredient. Ingredient data is used internally for duplicate detection. |
| S2-A5 | Dates (start/end) stored as epoch-ms of midnight UTC via `LocalDate.atStartOfDay(ZoneOffset.UTC)`. | Medication start/end dates are calendar dates with no time component; midnight UTC avoids timezone shifts and is consistent with Standards §2. |
| S2-A6 | Form selector uses M3 `FlowRow` + `FilterChip` (not a dropdown). | Flat chip layout shows all 9 options at once; faster tapping; aligns with DESIGN.md "one clear primary action" — form selection is not a secondary affordance. |
| S2-A7 | `AddEditMedicationViewModel` takes `SavedStateHandle` and reads `medId` nav arg for edit mode. Null = add mode. | Standards §9 — nav args carry IDs only; SavedStateHandle survives process death. |
| S2-A8 | Duplicate warning rendered as M3 `AlertDialog` with "Cancel" + "Save anyway" (Law 3 non-blocking). | Clear modal without navigation; user stays on the details screen after dismissal. |
| S2-A9 | Edit mode loads existing medication at VM init (via `loadExistingMedication`) and jumps to DETAILS step. | No need to re-resolve the drug from search; ingredient RxCUIs are already stored in `ingredientsJson`. |
| S2-A10 | `MedicationRepository.mapRxNormForm()` maps RxNorm form strings to `MedicationForm` enum via `contains` checks, falls through to OTHER. | RxNorm form strings are verbose ("Oral Tablet", "Extended-Release Capsule"); substring matching covers the common cases without a hard lookup table. |
| S2-A11 | `FLAG_SECURE` applied globally in `MainActivity.onCreate()` for this slice. | All screens currently rendered contain or lead to PHI (medication names, strengths). Deferred: per-screen refinement once onboarding/legal screens (Slice 6) are added. |
| S2-A12 | `lifecycle-runtime-compose` added explicitly for `collectAsStateWithLifecycle()`. | Not in the Compose BOM; needed for lifecycle-aware StateFlow collection in composables. |
