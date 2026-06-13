#!/usr/bin/env python3
"""
build_drug_db.py  —  Pharos drug-database build pipeline

Turns the NLM "RxNorm Current Prescribable Content" RRF files into the compact
SQLite database the app bundles and ships.

SOURCE (free, no UMLS login, no license):
    Download "RxNorm_full_prescribe_<date>.zip" from
    https://www.nlm.nih.gov/research/umls/rxnorm/docs/rxnormfiles.html
    Unzip it; the RRF files live in the "rrf/" folder inside.

USAGE:
    python3 build_drug_db.py /path/to/rrf  drug_db.sqlite

OUTPUT TABLES:
    drug_search   (rxcui, name, name_lower, tty)   -- everything the search box queries
    ingredient_map(drug_rxcui, ingredient_rxcui, ingredient_name)
                                                   -- powers duplicate-ingredient detection
    db_meta       (key, value)                     -- source + build date for Law 9 (provenance)
"""

import sqlite3
import sys
import os
from datetime import date

# Term types we keep for the search box. These are what users actually type or pick:
#   IN  = ingredient            PIN = precise ingredient     MIN = multi-ingredient
#   BN  = brand name            SCD = clinical drug          SBD = branded drug
#   SCDC/SBDC = drug components  GPCK/BPCK = drug packs
KEEP_TTYS = {"IN", "PIN", "MIN", "BN", "SCD", "SBD", "SCDC", "SBDC", "GPCK", "BPCK"}
INGREDIENT_TTYS = {"IN", "PIN", "MIN"}

# Relationship attributes that connect a drug/brand to its active ingredient.
INGREDIENT_RELAS = {
    "has_ingredient", "ingredient_of",
    "has_precise_ingredient", "precise_ingredient_of",
    "tradename_of", "has_tradename",
}


def parse_conso(rrf_dir):
    """Read RXNCONSO.RRF -> list of (rxcui, name, tty). English, non-suppressed, kept TTYs only."""
    path = os.path.join(rrf_dir, "RXNCONSO.RRF")
    rows = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            f = line.rstrip("\n").split("|")
            rxcui, lat, sab, tty, name, suppress = f[0], f[1], f[11], f[12], f[14], f[16]
            if lat != "ENG":
                continue
            if suppress == "O":          # obsolete/suppressed
                continue
            if tty not in KEEP_TTYS:
                continue
            if not name:
                continue
            rows.append((rxcui, name, tty))
    return rows


def parse_rel(rrf_dir, ingredient_rxcuis):
    """
    Read RXNREL.RRF -> set of (drug_rxcui, ingredient_rxcui).

    Direction-agnostic on purpose: RxNorm's RELA direction convention is a classic
    source of bugs, so instead of trusting it, we look at each ingredient-type
    relationship and treat whichever endpoint IS an ingredient as the ingredient,
    and the other endpoint as the drug. Correct regardless of row direction.
    """
    path = os.path.join(rrf_dir, "RXNREL.RRF")
    pairs = set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            f = line.rstrip("\n").split("|")
            rxcui1, rxcui2, rela = f[0], f[4], f[7]
            if rela not in INGREDIENT_RELAS:
                continue
            a_is_ing = rxcui1 in ingredient_rxcuis
            b_is_ing = rxcui2 in ingredient_rxcuis
            if a_is_ing and not b_is_ing:
                pairs.add((rxcui2, rxcui1))   # drug=rxcui2, ingredient=rxcui1
            elif b_is_ing and not a_is_ing:
                pairs.add((rxcui1, rxcui2))   # drug=rxcui1, ingredient=rxcui2
            # if both or neither are ingredients, skip (not a clean drug->ingredient edge)
    return pairs


def build(rrf_dir, out_path):
    conso = parse_conso(rrf_dir)
    names_by_rxcui = {rxcui: name for rxcui, name, tty in conso}
    ingredient_rxcuis = {rxcui for rxcui, name, tty in conso if tty in INGREDIENT_TTYS}
    edges = parse_rel(rrf_dir, ingredient_rxcuis)

    if os.path.exists(out_path):
        os.remove(out_path)
    con = sqlite3.connect(out_path)
    cur = con.cursor()

    cur.execute("""CREATE TABLE drug_search(
        rxcui TEXT, name TEXT, name_lower TEXT, tty TEXT)""")
    cur.executemany(
        "INSERT INTO drug_search VALUES (?,?,?,?)",
        [(rxcui, name, name.lower(), tty) for rxcui, name, tty in conso],
    )
    cur.execute("CREATE INDEX idx_name_lower ON drug_search(name_lower)")
    cur.execute("CREATE INDEX idx_rxcui ON drug_search(rxcui)")

    cur.execute("""CREATE TABLE ingredient_map(
        drug_rxcui TEXT, ingredient_rxcui TEXT, ingredient_name TEXT)""")
    cur.executemany(
        "INSERT INTO ingredient_map VALUES (?,?,?)",
        [(d, i, names_by_rxcui.get(i, "")) for d, i in edges],
    )
    cur.execute("CREATE INDEX idx_drug ON ingredient_map(drug_rxcui)")
    cur.execute("CREATE INDEX idx_ing ON ingredient_map(ingredient_rxcui)")

    # Optional: full-text search for fast as-you-type lookups (skipped if FTS5 absent).
    try:
        cur.execute("CREATE VIRTUAL TABLE drug_fts USING fts5(name, rxcui UNINDEXED)")
        cur.execute("INSERT INTO drug_fts(name, rxcui) SELECT name, rxcui FROM drug_search")
        fts = True
    except sqlite3.OperationalError:
        fts = False

    cur.execute("CREATE TABLE db_meta(key TEXT, value TEXT)")
    cur.executemany("INSERT INTO db_meta VALUES (?,?)", [
        ("source", "RxNorm Current Prescribable Content (NLM)"),
        ("built", date.today().isoformat()),
        ("name_count", str(len(conso))),
        ("ingredient_edges", str(len(edges))),
        ("fts5", "yes" if fts else "no"),
    ])

    con.commit()
    con.close()
    return len(conso), len(edges), fts


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("usage: python3 build_drug_db.py /path/to/rrf  out.sqlite")
        sys.exit(1)
    n, e, fts = build(sys.argv[1], sys.argv[2])
    print(f"built {sys.argv[2]}: {n} searchable names, {e} drug->ingredient edges, FTS5={'on' if fts else 'off'}")
