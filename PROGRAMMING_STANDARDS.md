# Pharos — Programming Standards (binding on every executor)

These standards are a **gate**, not advice. Every slice obeys them by construction.
They outrank convenience and speed. When a standard conflicts with a feature, the
standard wins (mirrors the Constitution's precedence rule). Items marked
**[LAUNCH-BLOCKER]** must be true before any Play Store submission; an executor that
cannot satisfy one flags `DONE_WITH_CONCERNS` and logs it in `TODO.md`.

Sourced from three research sweeps (production blind-spots, Android health-app
security, i18n/l10n) run 2026-06-12. Inline citations kept where they pin a version
or deadline.

---

## 0. The verification loop (run before any DONE)

From repo root, with `source ./env.sh` first:

```
./gradlew --no-daemon :app:lintDebug \
                      :app:testDebugUnitTest \
                      :app:assembleDebug
```

All three green = gate passes. Add `:app:connectedDebugAndroidTest` only when an
emulator/device is attached (instrumented tests are Dave's on-device pass; do not
block the pipeline on them — see PIPELINE.md §Testing reality).

- `lintDebug` must report **zero errors** (the i18n/security lint set in `app/lint.xml`).
- Never weaken `lint.xml` severities or add blanket `-dontwarn`/`baseline` to pass.
- Unit tests must be deterministic: pin the zone/clock, never read wall-clock `now()`
  in an assertion path.

---

## 1. Architecture & code style

- Kotlin official style (`kotlin.code.style=official`); 4-space indent; trailing commas.
- Single-Activity + Jetpack Compose. MVVM: `ViewModel` exposes immutable
  `StateFlow<UiState>`; UI is a pure function of state.
- Package by feature: `medication/`, `schedule/`, `alarm/`, `dose/`, `refill/`,
  `reference/`, `backup/`, `reliability/`, `onboarding/`, plus `core/` (time, crypto,
  db, util) and `data/` (Room, repositories).
- Two Room databases, never cross-joined: `RegimenDatabase` (R/W) and
  `DrugRefDatabase` (read-only, CDN-replaced). See spec §3.3.
- All I/O on `Dispatchers.IO` via coroutines. **Never** `allowMainThreadQueries()`.
- Dependency injection: manual constructor injection through an `AppContainer` held by
  `PharosApplication` (no Hilt/Dagger in v1 — keep the graph inspectable). Executors
  must not add a DI framework without a DECISIONS.md entry.
- No `!!` on nullable platform types without a guard; prefer `requireNotNull` with a
  message. No silent `catch {}` — every catch logs (no PHI) and either recovers
  meaningfully or rethrows. (Mirrors the silent-failure rule.)

## 2. Time correctness — **[LAUNCH-BLOCKER]** (spec §3.4, §4.3)

A wrong-hour dose is a safety bug. These are non-negotiable:

- **Store instants, not wall-clock strings.** Persist `Instant`/epoch-millis (UTC) for
  timestamps; persist schedule *intent* as `LocalTime`/`LocalDate` + an explicit
  `ZoneId`. **Never** store `LocalDateTime` for an alarm trigger — it has no zone and
  its epoch shifts under DST.
- **Compute from an explicit `ZoneId`,** never the JVM default implicitly. Derive
  display zone from `ZoneId.systemDefault()` only at format time.
- **Schedule-anchored by default** (DECISIONS.md D1): next dose computed from the
  schedule, not last-taken, unless the med opts into "interval from last dose."
- Handle DST gap (02:00–03:00 spring-forward → shift forward) and overlap (fall-back →
  earlier offset) explicitly; cover both with unit tests crossing a real US DST
  boundary. `core/time/DoseClock.kt` is the canonical home; all scheduling routes
  through it.
- Re-register all alarms on `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `TIME_SET`,
  `TIMEZONE_CHANGED`, `ACTION_DATE_CHANGED`, app-update, and a daily midnight rollover.
- Unit tests set the test zone explicitly and advance across spring-forward; UTC-only
  tests do not satisfy this section.

## 3. Alarm engine — **[LAUNCH-BLOCKER]** (spec §2.8, §3.4)

- Declare **`USE_EXACT_ALARM`** (auto-granted for a dedicated reminder app) as the
  primary; also handle `SCHEDULE_EXACT_ALARM` revocation. Gate every exact call on
  `AlarmManager.canScheduleExactAlarms()`; on false, fall back to `setWindow()` — never
  drop the reminder. ([dev.android schedule-exact-alarms](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms))
- Register a receiver for `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` and
  reschedule when permission state changes.
- Use **`setAlarmClock()`** for the dose-due alert (carries `FLAG_WAKE_FROM_IDLE`,
  bypasses Doze 9-minute coalescing that throttles `setExactAndAllowWhileIdle`).
- **Single-fire-and-reschedule** only. Never `setRepeating`. The receiver computes and
  schedules the next instance on every fire; failing to do so silently stops all later
  doses.
- `USE_FULL_SCREEN_INTENT`: check `canUseFullScreenIntent()` at runtime (Android 14
  gates it); route to settings if denied. Declare on Play Console.
- Receiver work that exceeds the ~10s window uses `goAsync()`; any DB call inside
  `onReceive` dispatches off the main thread (ANR risk).
- All alarm `PendingIntent`s use `FLAG_IMMUTABLE` (+ `FLAG_UPDATE_CURRENT`); one-shot
  actions add `FLAG_ONE_SHOT`. Missing mutability flag crashes on API 31+.
- Runtime-registered receivers pass `RECEIVER_NOT_EXPORTED` (or `_EXPORTED`) explicitly
  (Android 14 throws otherwise).
- Document, in the reliability dashboard, the per-OEM battery-exemption path; OEM
  killers (Samsung Sleeping Apps, Xiaomi autostart, Huawei PowerGenie) are real and not
  emulator-testable — surface guidance + dontkillmyapp.com (DECISIONS.md D5).

## 4. Notifications & channels (spec Law 1, §2.8)

- **Dose channel is sacred:** `IMPORTANCE_HIGH`, created at first install (importance
  cannot be raised later), carries dose-due alerts *only*. Refill/low-supply gets a
  **separate** channel; donations/re-engagement are in-app only, never a notification.
- `POST_NOTIFICATIONS` is runtime on API 33+; request with rationale, version-gate the
  check. Alarms fire but notifications are silently dropped if not granted — surface
  this in the dashboard.
- Foreground service (if any) targeting API 34+ declares the `health` FGS type in
  manifest and Play Console, else runtime throw.

## 5. Persistence / Room (spec §3.3, §3.5)

- `exportSchema = true`; schemas committed under `app/schemas/` (already wired via
  `ksp { arg("room.schemaLocation", ...) }`). Add `androidTest` asset srcDir when
  instrumented migration tests land.
- **Never `fallbackToDestructiveMigration()` in a path users reach.** Dose history is
  append/transition-only; a destructive migration is data loss = safety event.
- Migration SQL uses literal column names matching the committed schema JSON; every
  version bump ships a migration + a `MigrationTestHelper` test from v1→current.
- Checkpoint WAL (`PRAGMA wal_checkpoint(TRUNCATE)`) before copying the DB for backup.
- Drug-ref DB and regimen DB carry independent schema versions; app refuses a
  newer-than-understood schema and keeps the last good DB.

## 6. Security — **[LAUNCH-BLOCKER for items so marked]** (spec Laws 4,5,7,9; §3.6)

- **At-rest encryption of the regimen DB [LAUNCH-BLOCKER]:** SQLCipher via
  `net.zetetic:sqlcipher-android` (≥4.13.0 — the old `android-database-sqlcipher` fails
  the 16KB page-size requirement). DB key is 32 random bytes from `SecureRandom`,
  wrapped by an Android Keystore AES-256-GCM key (Google **Tink** `AndroidKeysetManager`
  with `android-keystore://` master URI — `androidx.security:security-crypto` is
  deprecated, do not use). StrongBox-back the wrapping key when
  `FEATURE_STRONGBOX_KEYSTORE` is present, catching `StrongBoxUnavailableException` →
  TEE fallback. **Do not** set `setUserAuthenticationRequired(true)` on the DB key —
  alarms must read the DB while the device is locked.
- **Backup encryption (spec §2.12) [LAUNCH-BLOCKER]:** AES-256-GCM, `AES/GCM/NoPadding`
  from the JCE provider. Key from passphrase via Argon2id (m=64MiB,t=3,p=4) — PBKDF2-
  HMAC-SHA256 ≥600k iters only as fallback. 16-byte random salt + 12-byte random nonce
  per file, never reused; 128-bit tag; envelope header (`magic|version|kdf_id|params|
  salt|nonce|len`) is authenticated as AAD and **versioned**. Zero key/`CharArray`
  material after use. Reject corrupt/partial files via tag failure before any plaintext.
- **No health data in cloud/device-transfer backup [LAUNCH-BLOCKER]:** `allowBackup=false`
  + `dataExtractionRules` + legacy `fullBackupContent` all exclude database/sharedpref/
  file/external (already in the manifest scaffold). Tink keyset path must be excluded.
- **Network [LAUNCH-BLOCKER for integrity]:** cleartext disabled via
  `network_security_config` (scaffolded). Pin the CDN (Cloudflare) SPKI with a backup
  pin + expiry; do **not** pin openFDA/NLM (gov rotation). Validate every downloaded
  drug-DB against an **Ed25519-signed manifest** (SHA-256 + size; public key embedded in
  APK) before the atomic swap — a bad push is a safety event.
- **App surface:** every component `android:exported="false"` unless it must be callable;
  system-triggered receivers (alarm/boot) carry a custom `android:permission`. Apply
  `FLAG_SECURE` to screens rendering health data (dose history, med list, schedule,
  backup passphrase). Validate deep links against an allowlist; never forward an
  untrusted `Intent`. `filterTouchesWhenObscured=true` on dose-confirm + passphrase.
- **Secrets:** none in VCS/`BuildConfig`/APK. `.gitignore` covers `local.properties`,
  `env.sh`, `*.jks`, `keystore.properties`. The embedded Ed25519 *public* key is not a
  secret. Signing key lives out-of-tree; enroll Play App Signing at first upload.
- **Logging [LAUNCH-BLOCKER]:** R8 strips `Log.v/d/i` in release (rule in
  `proguard-rules.pro`). **Never** pass med names, dose times, or passphrase-derived
  material to any log at any level; wrap non-constant args in `BuildConfig.DEBUG`.
- **SQL injection:** no `@RawQuery`/`execSQL` with user strings; dynamic sort maps an
  enum to a compile-time column name, never concatenation.
- **Crash handling, no 3rd-party SDK:** `Thread.setDefaultUncaughtExceptionHandler`
  chained to the previous handler, PII-stripped, local file only. Any crash/error
  reporting is opt-in, off by default, self-hosted if ever added (Law 4).
- **Proportionality:** do not block rooted devices; do not ship self-signature checks.
  Play Integrity deferred to v2 (no server to enforce against).

## 7. Localization — **[gate via lint]** (built locale-friendly from commit 1)

v1 ships en-US only; zero code surgery to add a locale later. Enforced by `app/lint.xml`
(`HardcodedText`, `SetTextI18n`, `StringFormatMatches`, `MissingTranslation`,
`RtlHardcoded`, `RtlEnabled`, `SpUsage` = error).

- **No hardcoded user-facing strings.** Everything via `stringResource(...)`; units
  ("mg", "mL", "tablet") are strings. No literal in a `Text(...)` or XML `android:text`.
- **Plurals** via `<plurals>` + `pluralStringResource`, never `count + "s"`. Always
  include `other` and put `%d` inside `one`. Beyond one/other (gender, ordinals) use ICU
  `android.icu.text.MessageFormat`.
- **Positional args** (`%1$s`, `%2$s`) in every multi-arg string; never concatenate
  translated fragments. No contractions/possessives/`he/she` slashes in strings.
- **Date/time display** via locale-aware formatters only: `DateFormat.getBestDateTimePattern`
  / ICU `DateTimePatternGenerator` `j`-skeleton (honors the device 12/24h toggle). Never
  hardcode `"MM/dd"`, `"HH:mm"`, `"08:00 AM"`, or a `:`/`/` separator for display. Storage
  stays ISO/epoch (see §2 — the storage-vs-display line is hard).
- **Numbers** via `NumberFormat.getInstance()` (locale decimal separator); never
  `number.toString()` for display.
- **Case/sort:** `uppercase(Locale.ROOT)` for machine logic (the Turkish-i trap),
  device locale only for display; sort name lists with `android.icu.text.Collator`.
- **RTL ready:** `supportsRtl=true`; `start/end` not `left/right`; `autoMirrored=true`
  on directional icons; Compose padding uses `start`/`end`. Debug builds run pseudolocales
  (`isPseudoLocalesEnabled=true`, already set) — verify en-XA expansion / ar-XB mirroring.
- **Per-app language infra:** `locale_config.xml` + (later) AppCompat
  `setApplicationLocales` wired even with one locale.
- Default `res/values/strings.xml` is always complete (missing default = crash).
  `translatable="false"` on URLs/constants; `<xliff:g>` around brand/drug/number spans.

## 8. Accessibility — **[LAUNCH-BLOCKER]** (Law 10, spec §2.15)

- TalkBack `contentDescription` on every actionable control (lint `ContentDescription`
  = error). Text in `sp`; respect font scaling, no truncation under large fonts.
- Touch targets ≥48dp. No color-only signals — every warning is icon **+** text.
- Core flows (add med, mark taken, snooze, skip, view schedule) operable one-handed,
  under TalkBack, and at max font scale. Failures block release.

## 9. Compose & lifecycle hygiene

- Hoist state to the lowest common owner; UI models are `@Immutable`/`val`-only for
  skippability. `LazyColumn` items get stable `key`s.
- `rememberSaveable` for UI state that must survive process death; partial form state
  persists to Room or `SavedStateHandle` immediately (process death loses `ViewModel`
  fields without it). Nav args carry IDs only, never complex objects.
- No `Activity`/composable-lambda references stored in a `ViewModel` (leaks); use
  `applicationContext` for singletons. Every `DisposableEffect` has an `onDispose`.

## 10. What AI/humans forget (cross-cutting catch-all)

- Wire `BOOT_COMPLETED` **and** require a first user launch (Android 13+) before relying
  on it; `RECEIVE_BOOT_COMPLETED` declared.
- Release build correctness: keep rules for Room entities, serializers, receivers;
  test the **release** (minified) APK builds and runs, not just debug.
- StrictMode on in debug; treat violations as bugs.
- Target API level / 16KB page size / FGS-type / exact-alarm / full-screen-intent are
  Play **review** gates, not automatic — see §3, §6, and Launch Gates (spec Part IV).
- Process-death restore: the active-alarm list and dose states must rebuild from Room on
  cold start; test with `am kill` / `ActivityScenario.recreate()` where an emulator exists.
- Robolectric `ShadowAlarmManager` unit-tests scheduling without a device — use it.

## 11. Git & commit discipline

- Conventional commits (`feat:`/`fix:`/`refactor:`/`docs:`/`test:`/`chore:`). SemVer from
  0.1.0. No secrets in commits. Each executor commits **locally**; the final release
  executor performs the single `git push` (see PIPELINE.md §Push discipline).
- Pre-1.0 ⇒ **no** `.github/workflows/*.yml` (CI minutes policy). The local verification
  loop (§0) is the gate.
- SSH from the sandbox needs `git -c core.sshCommand='ssh -F ~/.ssh/config'` (already set
  via `git config core.sshCommand`).
