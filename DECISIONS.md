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

## Open decisions deferred to their slice (not blockers)

- App package id confirmed `com.beryndil.pharos`.
- Drug-DB CDN host/bucket names: placeholder until Dave provisions Backblaze B2 +
  Cloudflare. The pipeline (Slice 8 / parallel track) builds against a documented
  contract and a local fixture; **provisioning the real bucket and DNS is a Dave task**
  (account creation + payment) — logged in TODO.md, not a code blocker.
- Release signing keystore: generated and stored out-of-tree at first release-build
  need; Play App Signing enrollment is a Dave/console task.
