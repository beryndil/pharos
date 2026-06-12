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

## Open decisions deferred to their slice (not blockers)

- App package id confirmed `com.beryndil.pharos`.
- Drug-DB CDN host/bucket names: placeholder until Dave provisions Backblaze B2 +
  Cloudflare. The pipeline (Slice 8 / parallel track) builds against a documented
  contract and a local fixture; **provisioning the real bucket and DNS is a Dave task**
  (account creation + payment) — logged in TODO.md, not a code blocker.
- Release signing keystore: generated and stored out-of-tree at first release-build
  need; Play App Signing enrollment is a Dave/console task.
