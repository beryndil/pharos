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

## Open decisions deferred to their slice (not blockers)

- App package id confirmed `com.beryndil.pharos`.
- Drug-DB CDN host/bucket names: placeholder until Dave provisions Backblaze B2 +
  Cloudflare. The pipeline (Slice 8 / parallel track) builds against a documented
  contract and a local fixture; **provisioning the real bucket and DNS is a Dave task**
  (account creation + payment) — logged in TODO.md, not a code blocker.
- Release signing keystore: generated and stored out-of-tree at first release-build
  need; Play App Signing enrollment is a Dave/console task.

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
