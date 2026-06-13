#!/usr/bin/env python3
"""
build_bundled_db.py — Pharos bundled drug-DB builder (RxNav REST API)

Builds the in-APK drug reference database from the open, keyless RxNav REST API.
No UMLS key required. Emits the same schema as build_drug_db.py (the full CDN pipeline).

USAGE:
    python3 tools/build_bundled_db.py

OUTPUT:
    tools/build/drug_db.sqlite

SCHEMA (identical to build_drug_db.py):
    drug_search(rxcui TEXT, name TEXT, name_lower TEXT, tty TEXT)
    ingredient_map(drug_rxcui TEXT, ingredient_rxcui TEXT, ingredient_name TEXT)
    db_meta(key TEXT, value TEXT)
    drug_fts (FTS5 virtual table, optional — skipped if FTS5 absent)

API:  https://rxnav.nlm.nih.gov/REST  (no key, open)
"""

import json
import os
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date

RXNAV_BASE = "https://rxnav.nlm.nih.gov/REST"
CALL_DELAY_S = 0.05       # polite rate limit: 50 ms between requests
RETRY_DELAY_S = 2.0
MAX_RETRIES = 1
REQUEST_TIMEOUT_S = 20

_THIS_DIR = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(_THIS_DIR, "build")
OUT_PATH = os.path.join(OUT_DIR, "drug_db.sqlite")

# ---------------------------------------------------------------------------
# Curated list of commonly prescribed US medications (generic/combo names).
# Sorted for deterministic rebuilds; duplicates removed by set().
# ---------------------------------------------------------------------------
_DRUG_LIST_RAW = [
    # Statins / lipid
    "atorvastatin", "rosuvastatin", "simvastatin", "pravastatin", "lovastatin",
    "fluvastatin", "pitavastatin",
    # ACE inhibitors
    "lisinopril", "enalapril", "ramipril", "benazepril", "captopril",
    "quinapril", "fosinopril", "perindopril",
    # ARBs
    "losartan", "valsartan", "olmesartan", "irbesartan", "candesartan",
    "telmisartan", "azilsartan",
    # Calcium channel blockers
    "amlodipine", "nifedipine", "diltiazem", "verapamil", "felodipine",
    "nicardipine",
    # Beta-blockers
    "metoprolol succinate", "metoprolol tartrate", "carvedilol", "atenolol",
    "bisoprolol", "nebivolol", "propranolol", "labetalol", "nadolol",
    "acebutolol",
    # Diuretics
    "hydrochlorothiazide", "furosemide", "spironolactone", "eplerenone",
    "chlorthalidone", "indapamide", "torsemide", "bumetanide", "triamterene",
    "amiloride",
    # Antiarrhythmics / cardiac misc
    "digoxin", "amiodarone", "flecainide", "sotalol", "dronedarone",
    "ivabradine", "hydralazine", "clonidine", "doxazosin", "prazosin",
    "terazosin", "isosorbide mononitrate", "isosorbide dinitrate",
    "nitroglycerin", "ranolazine",
    # Anticoagulants / antiplatelets
    "warfarin", "apixaban", "rivaroxaban", "dabigatran", "edoxaban",
    "clopidogrel", "ticagrelor", "prasugrel", "aspirin", "cilostazol",
    "dipyridamole",
    # Diabetes — oral / injectable
    "metformin", "glipizide", "glyburide", "glimepiride",
    "sitagliptin", "saxagliptin", "linagliptin", "alogliptin",
    "empagliflozin", "canagliflozin", "dapagliflozin",
    "liraglutide", "semaglutide", "dulaglutide", "exenatide",
    "pioglitazone",
    "insulin glargine", "insulin detemir", "insulin degludec",
    "insulin aspart", "insulin lispro", "insulin regular human",
    # Thyroid
    "levothyroxine", "liothyronine", "methimazole", "propylthiouracil",
    # Respiratory
    "albuterol", "ipratropium", "tiotropium", "salmeterol", "formoterol",
    "fluticasone propionate", "budesonide", "mometasone furoate",
    "beclomethasone dipropionate", "montelukast", "zafirlukast", "theophylline",
    "roflumilast", "umeclidinium", "indacaterol", "aclidinium",
    # GI
    "omeprazole", "pantoprazole", "esomeprazole", "lansoprazole",
    "rabeprazole", "famotidine", "metoclopramide",
    "ondansetron", "promethazine", "prochlorperazine", "dicyclomine",
    "hyoscyamine", "mesalamine", "sulfasalazine", "loperamide",
    "polyethylene glycol 3350",
    # CNS / Antidepressants
    "sertraline", "fluoxetine", "paroxetine", "citalopram", "escitalopram",
    "fluvoxamine", "venlafaxine", "duloxetine", "desvenlafaxine",
    "bupropion", "mirtazapine", "trazodone",
    "amitriptyline", "nortriptyline", "imipramine", "desipramine",
    "clomipramine", "doxepin",
    # Mood stabilizers / Antiepileptics
    "lithium", "valproic acid", "carbamazepine", "lamotrigine",
    "levetiracetam", "topiramate", "gabapentin", "pregabalin",
    "phenytoin", "zonisamide", "oxcarbazepine", "lacosamide",
    "ethosuximide", "phenobarbital", "primidone",
    # Anxiolytics / Sedatives
    "alprazolam", "lorazepam", "diazepam", "clonazepam",
    "temazepam", "triazolam", "chlordiazepoxide",
    "zolpidem", "eszopiclone", "zaleplon", "ramelteon",
    "buspirone", "hydroxyzine",
    # Antipsychotics
    "quetiapine", "risperidone", "olanzapine", "aripiprazole",
    "ziprasidone", "haloperidol", "clozapine", "lurasidone",
    "asenapine", "brexpiprazole", "cariprazine", "paliperidone",
    # ADHD
    "methylphenidate", "amphetamine", "dextroamphetamine",
    "lisdexamfetamine", "atomoxetine",
    # Dementia
    "donepezil", "rivastigmine", "galantamine", "memantine",
    # Wakefulness
    "modafinil", "armodafinil",
    # NSAIDs / Analgesics
    "ibuprofen", "naproxen", "celecoxib", "diclofenac",
    "indomethacin", "meloxicam", "ketorolac", "piroxicam",
    "etodolac", "flurbiprofen", "sulindac",
    "acetaminophen", "tramadol",
    # Muscle relaxants
    "cyclobenzaprine", "baclofen", "tizanidine", "methocarbamol",
    "carisoprodol",
    # Rheumatology / gout / bone
    "methotrexate", "hydroxychloroquine", "leflunomide",
    "allopurinol", "colchicine", "febuxostat", "probenecid",
    "alendronate", "risedronate", "ibandronate", "raloxifene",
    # Opioids / controlled
    "morphine", "oxycodone", "hydrocodone", "hydromorphone",
    "fentanyl", "buprenorphine", "naloxone", "naltrexone",
    "codeine", "tapentadol",
    # Corticosteroids
    "prednisone", "methylprednisolone", "dexamethasone",
    "prednisolone", "hydrocortisone", "triamcinolone",
    "betamethasone", "fludrocortisone",
    # Antibiotics — penicillins
    "amoxicillin", "ampicillin", "penicillin V potassium",
    "dicloxacillin", "oxacillin",
    # Antibiotics — macrolides
    "azithromycin", "clarithromycin", "erythromycin",
    # Antibiotics — tetracyclines
    "doxycycline", "minocycline", "tetracycline",
    # Antibiotics — fluoroquinolones
    "ciprofloxacin", "levofloxacin", "moxifloxacin", "ofloxacin",
    # Antibiotics — cephalosporins
    "cephalexin", "cefdinir", "cefadroxil", "cefuroxime axetil",
    "cefprozil", "cefpodoxime",
    # Antibiotics — other
    "clindamycin", "metronidazole", "nitrofurantoin",
    "vancomycin", "linezolid", "trimethoprim", "sulfamethoxazole",
    # Antifungals / Antivirals
    "fluconazole", "itraconazole", "voriconazole",
    "acyclovir", "valacyclovir", "famciclovir", "oseltamivir",
    # HIV ARVs
    "tenofovir disoproxil fumarate", "emtricitabine",
    "abacavir", "lamivudine", "efavirenz", "dolutegravir",
    "rilpivirine", "atazanavir",
    # Hormones / Reproductive
    "estradiol", "progesterone", "medroxyprogesterone",
    "norethindrone", "levonorgestrel", "etonogestrel",
    "testosterone", "finasteride", "dutasteride",
    "tamoxifen", "letrozole", "anastrozole", "exemestane",
    "sildenafil", "tadalafil", "vardenafil",
    # Ophthalmology
    "latanoprost", "bimatoprost", "travoprost", "brimonidine",
    "timolol", "dorzolamide", "brinzolamide",
    # Misc supplements / electrolytes
    "folic acid", "cholecalciferol", "ferrous sulfate",
    "potassium chloride", "magnesium oxide",
    # Combination products
    "lisinopril/hydrochlorothiazide",
    "amlodipine/atorvastatin",
    "amlodipine/benazepril",
    "amlodipine/valsartan",
    "amlodipine/olmesartan",
    "valsartan/hydrochlorothiazide",
    "losartan/hydrochlorothiazide",
    "olmesartan/hydrochlorothiazide",
    "irbesartan/hydrochlorothiazide",
    "telmisartan/hydrochlorothiazide",
    "metformin/sitagliptin",
    "metformin/glipizide",
    "metformin/pioglitazone",
    "metformin/saxagliptin",
    "metformin/empagliflozin",
    "amphetamine/dextroamphetamine",
    "acetaminophen/codeine",
    "acetaminophen/hydrocodone",
    "acetaminophen/oxycodone",
    "fluticasone/salmeterol",
    "budesonide/formoterol",
    "umeclidinium/vilanterol",
    "ipratropium/albuterol",
    "trimethoprim/sulfamethoxazole",
    "emtricitabine/tenofovir disoproxil fumarate",
    "abacavir/lamivudine",
    "dorzolamide/timolol",
    "buprenorphine/naloxone",
]
CURATED_DRUGS = sorted(set(_DRUG_LIST_RAW))


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def http_get(url: str) -> dict | None:
    """GET url, return parsed JSON dict or None on any failure. Retries once."""
    for attempt in range(MAX_RETRIES + 1):
        try:
            req = urllib.request.Request(url, headers={"Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_S) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as exc:
            if attempt < MAX_RETRIES:
                print(f"  [retry] {exc}", flush=True)
                time.sleep(RETRY_DELAY_S)
            else:
                print(f"  [err] {exc}", flush=True)
                return None
    return None


def api_get(path: str) -> dict | None:
    """GET a RxNav REST path, enforce CALL_DELAY after each call."""
    result = http_get(f"{RXNAV_BASE}/{path}")
    time.sleep(CALL_DELAY_S)
    return result


# ---------------------------------------------------------------------------
# RxNav queries
# ---------------------------------------------------------------------------

def resolve_rxcui(name: str) -> str | None:
    """Resolve a drug name to its primary RxCUI (search=2 normalize)."""
    data = api_get(f"rxcui.json?name={urllib.parse.quote(name)}&search=2")
    if not data:
        return None
    ids = (data.get("idGroup") or {}).get("rxnormId") or []
    return ids[0] if ids else None


def get_properties(rxcui: str) -> tuple[str, str] | None:
    """Return (canonical_name, tty) for an rxcui, or None on failure."""
    data = api_get(f"rxcui/{rxcui}/properties.json")
    if not data:
        return None
    props = data.get("properties") or {}
    name = props.get("name", "").strip()
    tty = props.get("tty", "").strip()
    return (name, tty) if name and tty else None


def get_related(rxcui: str, tty: str) -> list[tuple[str, str]]:
    """Return [(rxcui, name), ...] for concepts related to rxcui with the given TTY."""
    data = api_get(f"rxcui/{rxcui}/related.json?tty={tty}")
    if not data:
        return []
    groups = (data.get("relatedGroup") or {}).get("conceptGroup") or []
    results = []
    for group in groups:
        for prop in (group.get("conceptProperties") or []):
            r = prop.get("rxcui", "").strip()
            n = prop.get("name", "").strip()
            if r and n:
                results.append((r, n))
    return results


def fetch_all_ingredient_names() -> dict[str, str]:
    """
    Fetch all IN concepts from allconcepts endpoint for authoritative names.
    Returns empty dict on failure (non-fatal — names gathered per-drug as fallback).
    """
    print("Fetching allconcepts?tty=IN for authoritative ingredient names...", flush=True)
    data = api_get("allconcepts.json?tty=IN")
    if not data:
        print("  [warn] allconcepts fetch failed; will use per-drug names", flush=True)
        return {}
    concepts = (data.get("minConceptGroup") or {}).get("minConcept") or []
    result = {c["rxcui"]: c["name"] for c in concepts if c.get("rxcui") and c.get("name")}
    print(f"  Loaded {len(result):,} authoritative ingredient names", flush=True)
    return result


# ---------------------------------------------------------------------------
# DB build
# ---------------------------------------------------------------------------

def build() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    if os.path.exists(OUT_PATH):
        os.remove(OUT_PATH)

    ingredient_names: dict[str, str] = fetch_all_ingredient_names()

    # (rxcui, tty) -> (rxcui, name, name_lower, tty) — dedup key is (rxcui, tty)
    drug_search: dict[tuple[str, str], tuple[str, str, str, str]] = {}
    # (drug_rxcui, ingredient_rxcui) -> ingredient_name
    edges: dict[tuple[str, str], str] = {}
    failed: list[str] = []

    total = len(CURATED_DRUGS)
    print(f"\nProcessing {total} curated drug names...\n", flush=True)

    for idx, drug_name in enumerate(CURATED_DRUGS):
        print(f"[{idx+1:3d}/{total}] {drug_name} ...", end=" ", flush=True)

        # 1. Resolve rxcui
        rxcui = resolve_rxcui(drug_name)
        if not rxcui:
            print("SKIP (unresolved rxcui)", flush=True)
            failed.append(drug_name)
            continue

        # 2. Canonical name + TTY
        props = get_properties(rxcui)
        if not props:
            print(f"SKIP (no properties; rxcui={rxcui})", flush=True)
            failed.append(drug_name)
            continue
        canon_name, tty = props

        # 3. Add generic/combo to drug_search
        ds_key = (rxcui, tty)
        if ds_key not in drug_search:
            drug_search[ds_key] = (rxcui, canon_name, canon_name.lower(), tty)

        # 4. Ingredient edges for this concept
        ingredients = get_related(rxcui, "IN")
        # Fallback: for IN/PIN concepts that are themselves the ingredient
        if not ingredients and tty in ("IN", "PIN"):
            ingredients = [(rxcui, ingredient_names.get(rxcui, canon_name))]
        # Update authoritative name cache
        for ing_rxcui, ing_name in ingredients:
            ingredient_names.setdefault(ing_rxcui, ing_name)
            edges[(rxcui, ing_rxcui)] = ingredient_names[ing_rxcui]

        # 5. Brand names (BN) for this concept
        brands = get_related(rxcui, "BN")
        for bn_rxcui, bn_name in brands:
            bn_key = (bn_rxcui, "BN")
            if bn_key not in drug_search:
                drug_search[bn_key] = (bn_rxcui, bn_name, bn_name.lower(), "BN")
            # Brand → same ingredients as the generic
            for ing_rxcui, ing_name in ingredients:
                edges[(bn_rxcui, ing_rxcui)] = ingredient_names.get(ing_rxcui, ing_name)

        print(
            f"rxcui={rxcui} tty={tty} ings={len(ingredients)} brands={len(brands)}",
            flush=True,
        )

    # ---------------------------------------------------------------------------
    # Write SQLite
    # ---------------------------------------------------------------------------
    print(f"\nWriting {OUT_PATH} ...", flush=True)
    con = sqlite3.connect(OUT_PATH)
    cur = con.cursor()

    cur.execute(
        "CREATE TABLE drug_search(rxcui TEXT, name TEXT, name_lower TEXT, tty TEXT)"
    )
    cur.executemany(
        "INSERT INTO drug_search VALUES (?,?,?,?)",
        list(drug_search.values()),
    )
    cur.execute("CREATE INDEX idx_name_lower ON drug_search(name_lower)")
    cur.execute("CREATE INDEX idx_rxcui ON drug_search(rxcui)")

    cur.execute(
        "CREATE TABLE ingredient_map"
        "(drug_rxcui TEXT, ingredient_rxcui TEXT, ingredient_name TEXT)"
    )
    cur.executemany(
        "INSERT INTO ingredient_map VALUES (?,?,?)",
        [(d, i, n) for (d, i), n in edges.items()],
    )
    cur.execute("CREATE INDEX idx_drug ON ingredient_map(drug_rxcui)")
    cur.execute("CREATE INDEX idx_ing ON ingredient_map(ingredient_rxcui)")

    # Optional FTS5
    fts_on = False
    try:
        cur.execute(
            "CREATE VIRTUAL TABLE drug_fts USING fts5(name, rxcui UNINDEXED)"
        )
        cur.execute(
            "INSERT INTO drug_fts(name, rxcui) SELECT name, rxcui FROM drug_search"
        )
        fts_on = True
    except sqlite3.OperationalError as exc:
        print(f"  [warn] FTS5 unavailable: {exc}; skipping", flush=True)

    drug_count = len(drug_search)
    edge_count = len(edges)

    cur.execute("CREATE TABLE db_meta(key TEXT, value TEXT)")
    cur.executemany(
        "INSERT INTO db_meta VALUES (?,?)",
        [
            ("source", "RxNorm via RxNav REST API (NLM)"),
            ("built", date.today().isoformat()),
            ("drug_count", str(drug_count)),
            ("ingredient_edge_count", str(edge_count)),
            ("bundled_subset", "true"),
            ("fts5", "yes" if fts_on else "no"),
        ],
    )

    con.commit()
    con.close()

    size_kb = os.path.getsize(OUT_PATH) // 1024
    print(f"\n{'='*60}")
    print(f"Built:  {OUT_PATH}")
    print(f"  drug_search rows : {drug_count:,}")
    print(f"  ingredient edges : {edge_count:,}")
    print(f"  file size        : {size_kb:,} KB")
    print(f"  FTS5             : {'on' if fts_on else 'off'}")
    print(f"  failed to resolve: {len(failed)} / {total} curated names")
    if failed:
        for f in failed:
            print(f"    - {f}")
    print(f"{'='*60}\n")


if __name__ == "__main__":
    build()
