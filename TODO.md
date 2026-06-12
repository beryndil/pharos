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

_(executors append here)_
