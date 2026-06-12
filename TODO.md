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
