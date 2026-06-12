# Pharos — Autonomous Build Pipeline

This is the orchestration plan to build Pharos through **v1.0**, unattended, using the
Beryndil orchestrator/executor/fixer/watchdog pattern over Bearings (127.0.0.1:8787).
The orchestrator session owns this file as its operating contract.

## Roles & models

- **Orchestrator** (`opus`): owns the slice list below, dispatches one executor at a
  time (worktree-serialized — see §Push discipline), arms a watchdog per dispatch,
  verifies artifacts before checking a slice done (synth-gate), never does slice work
  itself. Dispatches the next slice in the same turn it receives a callback.
- **Executor** (`sonnet` default; `opus` only for genuinely architectural slices — 4, 5):
  implements one slice end-to-end, runs the §0 verification loop in the **foreground**
  (timeout-wrapped), commits locally, posts `DONE`/`DONE_WITH_CONCERNS` back to the
  orchestrator, then self-closes its Bearings session.
- **Fixer** (`sonnet`; `opus` for architectural bugs): spawned by an executor stuck >2
  attempts on a bounded bug; fixes, runs the relevant gate subset, commits, posts back to
  the **executor**, self-closes.
- **Watchdog**: `~/.claude/scripts/bearings-watchdog.py <exec_id>` + a `ScheduleWakeup`
  fallback after every dispatch. Verdicts: `DONE_CLOSED` → verify artifacts + advance;
  `DEAD_AUTH` → re-dispatch on sonnet from current git state; `STALLED`/`DEAD_ERROR` →
  fixer or re-dispatch; `ALIVE` → reschedule.

## Environment recipe (every executor's first action)

```bash
cd /home/beryndil/Projects/active/pharos && source ./env.sh
```

`env.sh` is gitignored and already present. It sets, for this sandbox:
`ANDROID_HOME=~/.local/share/Android/Sdk`, `ANDROID_USER_HOME=~/.local/share/.android`,
`GRADLE_USER_HOME=~/.local/share/.gradle`, `JAVA_HOME=java-21-openjdk` (NOT 26 — AGP
breaks), and PATH. **The real `~/.android`, `~/.gradle`, and `$HOME` root are read-only**
in the build sandbox; do not try to write them. If `env.sh` is missing on a fresh
checkout, recreate it from DECISIONS.md A5.

Gradle: always `./gradlew --no-daemon` (the daemon is unreliable across sandboxed
sessions). Gates run in the **foreground**, timeout-wrapped (e.g.
`timeout 1500 ./gradlew --no-daemon ...`) — never backgrounded with a completion
notification, per reliability invariant 1.

## Push discipline (reliability invariants 2 & 5)

All executors share one git worktree. Therefore:

- **Serialize**: the orchestrator dispatches the next slice only after the current
  worktree-mutating executor has closed. No two executors mutate the tree concurrently.
- **Commit-local, push-once**: every executor `git commit`s locally; **only the final
  release executor (Slice 11) pushes.** A mid-pipeline push failure cannot half-publish.
  The GitHub repo (`Beryndil/pharos`, private) is created by the orchestrator before
  Slice 11 via `gh repo create` (no button/payment).

## Slice list (BUILD_SPEC.md Part VI order)

Each slice: implement → §0 verification loop green → commit local. Slices 4 & 5 are the
risk core — `opus` executors, test hardest. Foundation (Slice 1 scaffold) is **already
done** by the bootstrap session and committed; the orchestrator starts at Slice 1's
remaining schema work or Slice 2, after confirming the baseline build is green.

| # | Slice | Model | Key deliverables | Standards focus |
|---|-------|-------|------------------|-----------------|
| 0 | **Foundation (DONE pre-pipeline)** | — | Gradle/Compose skeleton, lint gate, security+locale manifest infra, `DoseClock` + DST tests, baseline build green | all |
| 1 | Schema & two-DB structure | sonnet | `RegimenDatabase` (SQLCipher) + `DrugRefDatabase`; append-only regimen entities (meds, schedules+phases, dose instances+state+timestamps, refills, settings); trimmed RxNorm bundle loader; schema export + migration test harness | §2,§5,§6 |
| 2 | Medication identity & entry | sonnet | Add/edit med with strength/form confirmation; local RxNorm resolution; free-text fallback; **duplicate-ingredient warning** (Law 3 phrasing) | §1,§7,§8 |
| 3 | Schedules | sonnet | Fixed, days-of-week, interval (D1), window, PRN, temporary, taper phases; pause/resume/end-date; all routed through `DoseClock` | §2,§7 |
| 4 | **Alarm engine & reliability** | **opus** | Single-fire-and-reschedule; all re-registration receivers; full-screen DUE alert; `setAlarmClock`; `setWindow` fallback; test-reminder path; DST/timezone math; `ShadowAlarmManager` tests | §2,§3,§10 |
| 5 | **Dose state machine** | **opus** | SCHEDULED/DUE/TAKEN/SNOOZED/SKIPPED/MISSED with D2/D3 rules; sacred dose channel; escalation; independent instances; append-only transitions | §2,§4,§5 |
| 6 | Onboarding + reliability dashboard | sonnet | Primed permission sequence (§2.14); reliability dashboard (§2.13) as the on-device analytics substitute | §3,§4,§8 |
| 7 | Refill tracking | sonnet | Per-med quantity/days-until-empty/refill-by; **own channel**; never suppress a dose on zero count | §4,§7 |
| 8 | Drug reference | sonnet | Per-drug openFDA/DailyMed fetch-and-cache with source+freshness; label-section reference text; Ed25519-verified CDN drug-DB pipeline + atomic swap | §5,§6 |
| 9 | Backup / restore / export | sonnet | Encrypted-JSON backup (D4, §6), restore + corrupt-file rejection, PDF+CSV export, post-wipe restore offer | §6 |
| 10 | Accessibility pass | sonnet | Full TalkBack/large-font/contrast pass on every core flow; one-handed check | §8 |
| 11 | Launch gates + release | sonnet | Play compliance text + permission justifications; ToS/privacy/medical-disclaimer in-app; testing-matrix scaffolding; **release (minified) build green**; version bump to 1.0.0; **single push** | §3,§6,§11 |

Parallel track (develop alongside 1–2, harden by 8): the RxNorm trim → compact SQLite
build job and the signed-manifest CDN update flow. Buildable against a local fixture;
real Backblaze/Cloudflare provisioning is a Dave task (TODO.md).

## Testing reality (be honest about the bar)

- **In-scope, automated, blocks the pipeline:** JVM unit tests + Robolectric (time math,
  state machine, schema/migrations, scheduling via `ShadowAlarmManager`, crypto/backup
  round-trips, duplicate detection). Lint gate. Debug **and** release build success.
- **Out-of-scope for the unattended run (Dave's on-device pass):** the spec §4.3 matrix
  that needs real hardware/emulator — reboot/Doze/DST/timezone *device* behavior across
  ≥3 OEM brands, TalkBack lived experience, full-screen-intent visuals. An emulator may
  be attempted (KVM is present) but is **not** allowed to block slice completion; if it
  won't run headless, the pipeline proceeds and these are flagged for Dave in TODO.md.
- "Production ready" for this run means: every slice implemented to spec + standards, all
  automated gates green, release build clean. Final human device-testing is Dave's,
  by his own statement.

## Definition of done (per slice, enforced by synth-gate)

1. Feature matches the spec section and obeys every relevant Standards section.
2. `./gradlew --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleDebug` green
   (foreground, verified by the orchestrator via the actual command output / git log, not
   a DONE string).
3. New logic has tests; time/crypto/state paths have edge-case tests.
4. Committed locally with a conventional-commit message. `TODO.md` updated for anything
   deferred.

## Autonomy contract

Never ask Dave A-or-B on a code call (decide, log in DECISIONS.md). Never idle between
callbacks. `BLOCKED` only for physical/credential/reachability walls (account creation,
payment, a real device) — and those are logged + skipped, never a stop. Code uncertainty
is decided, not deferred.
