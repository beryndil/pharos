# Pharos — Drug Database Build Pipeline

Two builders produce the drug reference database used by Pharos. Both emit the
**same schema** so the Android app reads either interchangeably.

---

## Schema (both builders)

```sql
drug_search(
    rxcui      TEXT,   -- RxNorm concept unique identifier
    name       TEXT,   -- canonical name as returned by RxNorm
    name_lower TEXT,   -- name.lower() for case-insensitive prefix search
    tty        TEXT    -- term type: IN, PIN, MIN, BN, SCD, SBD, …
)

ingredient_map(
    drug_rxcui       TEXT,   -- rxcui of the drug / brand product
    ingredient_rxcui TEXT,   -- rxcui of the active ingredient (IN/PIN)
    ingredient_name  TEXT    -- canonical ingredient name (for duplicate detection)
)

db_meta(
    key   TEXT,   -- 'source', 'built', 'drug_count', 'ingredient_edge_count',
                  -- 'bundled_subset', 'fts5'
    value TEXT
)

drug_fts  -- FTS5 virtual table over (name, rxcui); created if FTS5 is available
```

Indexes: `idx_name_lower` on `drug_search(name_lower)`, `idx_rxcui` on
`drug_search(rxcui)`, `idx_drug` on `ingredient_map(drug_rxcui)`, `idx_ing`
on `ingredient_map(ingredient_rxcui)`.

---

## A. Full DB — CDN pipeline (Dave / account step)

**Purpose:** The canonical, complete drug reference DB pushed to the CDN
(Backblaze B2 + Cloudflare). See BUILD_SPEC §3.2 / §3.5.  
**Data source:** NLM "RxNorm Current Prescribable Content" RRF files.

### Prerequisites

1. **Free UMLS account + API key.** NLM now requires UTS authentication to
   download the Current Prescribable Content ZIP. Register at
   <https://uts.nlm.nih.gov/uts/signup-login>. This is a Dave / account step —
   the API key cannot be automated here.

2. Download `RxNorm_full_prescribe_<date>.zip` from  
   <https://www.nlm.nih.gov/research/umls/rxnorm/docs/rxnormfiles.html>  
   (log in with your UMLS account; the download link appears on that page).

3. Unzip — the RRF files live in the `rrf/` folder inside the ZIP.

### Build

```bash
python3 tools/build_drug_db.py /path/to/rrf  drug_db.sqlite
```

Output is `drug_db.sqlite` at the given path.  
Expect ~100k+ `drug_search` rows and ~200k+ ingredient edges from the full
prescribable content set.

### Monthly rebuild

NLM releases updated RxNorm data monthly. Re-run this command against the
latest ZIP whenever the CDN DB is refreshed (typically monthly).

---

## B. Bundled DB — ships in the APK (no account needed)

**Purpose:** The ~400-drug representative subset bundled in `app/src/main/assets/`
for immediate offline use. Users get the full CDN DB as a background update.  
**Data source:** Open, keyless RxNav REST API  
(<https://rxnav.nlm.nih.gov/REST>).

### Build

```bash
timeout 600 python3 tools/build_bundled_db.py
```

Output: `tools/build/drug_db.sqlite`  
No key required. Requires network access to `rxnav.nlm.nih.gov`.

The script:
- Embeds a curated list of ~300 commonly prescribed US generic and combination
  drug names (sorted for deterministic rebuilds).
- Resolves each name to an RxCUI via `/REST/rxcui.json?name=…&search=2`.
- Fetches canonical properties (name, TTY) via `/REST/rxcui/{id}/properties.json`.
- Fetches related ingredients (IN) and brands (BN) per drug.
- Populates `drug_search` rows for generics and their brand-name variants.
- Populates `ingredient_map` edges for duplicate-ingredient detection.
- Fetches all authoritative ingredient names upfront via
  `/REST/allconcepts.json?tty=IN`.
- Politely rate-limits at 50 ms per call; retries transient failures once.

Expected output: several hundred `drug_search` rows, several hundred ingredient
edges, FTS5 on (when available).

### Placing the built DB for the Android app

After building, copy to the assets directory (done by slice G2b):

```bash
cp tools/build/drug_db.sqlite app/src/main/assets/drug_ref.db
```

`tools/build/` is `.gitignore`d — do not commit the binary.

---

## Rebuilding both

Run the CDN builder monthly (or after any RxNorm monthly release).  
Run the bundled builder whenever the curated drug list changes or the Android
schema version bumps. The two output files are independent.
