# Pharos v1 — Spec-update gaps (2026-06-12)

Two changes arrived after v1.1.x shipped: an updated `BUILD_SPEC.md` (§2.6) and a real
drug-database build script (`build_drug_db.py`). This file is the execution plan to close
both. Worked top-to-bottom as serialized executor slices (one at a time, shared worktree).

Tags: these ship as **v1.2.0** (both gaps), with the build full-gate (incl. `assembleRelease`).

---

## GAP 1 — Per-medication configurable miss window  (spec §2.6, RESOLVED)

Spec change: the 60-minute DUE→MISSED grace is now **per-medication configurable** (default 60),
mirroring the per-med `isCritical` model. Snooze rules unchanged in behavior (15-min, no fixed
cap, miss-window is the ceiling) — current code already conforms; add a regression test only.

**Slice G1 (one sonnet executor):**
- `MedicationEntity`: add `val missWindowMinutes: Int = 60`.
- `RegimenDatabase` v3→v4: `MIGRATION_3_4` = `ALTER TABLE medications ADD COLUMN missWindowMinutes INTEGER NOT NULL DEFAULT 60`; bump `@Database(version=4)` + `CURRENT_VERSION`; register migration; export `app/schemas/.../4.json`.
- `dose/MissWindow.kt`: replace hardcoded `GRACE_MS` use — `closeEpochMs(...)` takes a `graceLengthMs: Long` param. Keep windowed-dose behavior (window end wins) and "next scheduled dose, whichever first."
- `dose/DoseStateMachine.kt#computeMissClose`: read the medication's `missWindowMinutes` (medicationDao is already available for `medName`), convert to ms, pass as `graceLengthMs`.
- Add/Edit med UI (`AddEditMedicationScreen.kt` + `AddEditMedicationViewModel.kt` + `AddEditMedicationUiState`): a numeric "Reminder grace period (minutes)" field near the critical toggle; validate a sane range (e.g. 5–360), default 60; plain-language help. Strings in `strings.xml`.
- Tests: migration v3→v4 (MigrationHarness); state-machine miss-close honoring a custom value (tight + loose); snooze caps at the custom miss-close; VM field plumbing.
- Light gate; commit `feat(dose): per-medication configurable miss window (spec §2.6)`; no push.

---

## GAP 2 — Real RxNorm drug database + build pipeline

`build_drug_db.py` (Dave-supplied) is the canonical builder. Output schema:
- `drug_search(rxcui, name, name_lower, tty)` — the search index (IN/PIN/MIN/BN/SCD/SBD/…).
- `ingredient_map(drug_rxcui, ingredient_rxcui, ingredient_name)` — drug→ingredient edges (duplicate detection).
- `db_meta(key, value)` — source + build date (Law 9 provenance).
- `drug_fts` (FTS5) — optional fast prefix search.

**Decision:** align the app's read-only DrugRef schema to the builder's output (NOT the reverse).
The provided script is the pipeline of record; its schema maps directly to the app's two needs
(name search + ingredient resolution for duplicate detection). Strength/form stay **user-entered**
fields (already present) — RxNorm encodes them in the SCD/SBD name, not as columns; best-effort
parse is a nicety, not required for v1.

### Slice G2a — pipeline + data (python/ops; orchestrator-run or one sonnet executor)
- Commit `build_drug_db.py` to `tools/build_drug_db.py` + `tools/README.md` (download URL, usage, schema, monthly-rebuild note → feeds the CDN pipeline in §3.2/§3.5).
- Download RxNorm Current Prescribable Content (free, no UMLS login), run the builder → `drug_db.sqlite`. Record row counts + size in DECISIONS.md.
- Decide bundle size: if the full CPC build ≤ ~40 MB, bundle as-is; else ship a representative
  prescribable subset as the bundled asset and document that the full DB ships via CDN.
  Output the chosen `drug_ref.db` for G2b to bundle + test against.

### Slice G2b — Android schema alignment (one sonnet executor; depends on G2a)
- Replace `IngredientEntity`/`ProductEntity` with `DrugSearchEntity(rxcui, name, nameLower, tty)`
  and `IngredientMapEntity(drugRxcui, ingredientRxcui, ingredientName)`; keep/extend `db_meta`
  read for source+freshness in UI (Law 9). Bump `DrugRefDatabase` version + exported schema.
- DAOs: search by `name_lower` (LIKE prefix) and/or FTS; `ingredientsForDrug(rxcui)` from `ingredient_map`.
- `MedicationRepository`: `searchDrugs()` → name+rxcui+resolved ingredient names; `checkDuplicateIngredients()` reads `ingredient_map` (medication still stores `ingredientsJson` resolved at add-time — duplicate detection unchanged in behavior, safety preserved).
- `BundledDrugRefLoader` + `DrugRefDatabaseFactory.SeedCallback`: load the new-schema asset; replace `assets/drug_ref_fixture.db` with the real/subset DB from G2a.
- CDN: bump `dbSchemaVersion` to 2 in `DrugRefDatabaseFactory.CURRENT_VERSION`/manifest guard.
- Update tests (DrugRefDatabaseTest, MedicationRepository search/duplicate tests) to the new schema; keep a small deterministic fixture for unit tests if the real DB is too large for CI.
- Light gate; commit `feat(drugref): real RxNorm schema + bundled DB (build_drug_db.py pipeline)`; no push.

---

## Release
After G1 + G2a + G2b land and the **full** gate (incl. `assembleRelease`) is green → bump
**v1.2.0**, tag, push, and publish the signed APK to GitHub Releases via `scripts/build-release.sh --publish`.

## Out of scope (unchanged)
Section B CDN hosting (Backblaze/Cloudflare URL, real Ed25519 keypair, SPKI pinning) and
Section C device matrix remain Dave/physical-device items in TODO.md.
