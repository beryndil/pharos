#!/usr/bin/env python3
"""
Resilient comprehensive Pharos bundled drug DB from open RxNav (NO UMLS key).
Phase 1: pull all IN/PIN/MIN/BN, write a VALID ingredient-complete DB immediately
         (every generic searchable + self-mapped) and commit.
Phase 2: enrich PIN/MIN/BN -> base-IN edges concurrently, INSERTING INCREMENTALLY
         (commit every batch), so any interruption still leaves a strictly-better DB.
Schema-of-record: drug_search / ingredient_map / db_meta / drug_fts.
"""
import json, os, sqlite3, time, urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date

BASE = "https://rxnav.nlm.nih.gov/REST"
OUT_DIR = "/tmp/drug_full"
OUT = os.path.join(OUT_DIR, "drug_db.sqlite")
PROG = os.path.join(OUT_DIR, "progress.log")
UA = {"User-Agent": "Pharos-drugdb-builder/2.0 (medication reminder app)"}
WORKERS = 24

def log(m):
    line = f"[{time.strftime('%H:%M:%S')}] {m}"
    print(line, flush=True)
    with open(PROG, "a") as fh: fh.write(line + "\n")

def get(url, tries=4):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
                return json.loads(r.read().decode())
        except Exception:
            if i == tries - 1: return None
            time.sleep(0.3 * (i + 1))
    return None

def all_concepts(tty):
    d = get(f"{BASE}/allconcepts.json?tty={tty}")
    return d.get("minConceptGroup", {}).get("minConcept", []) if d else []

def related_ins(rxcui):
    d = get(f"{BASE}/rxcui/{rxcui}/related.json?tty=IN")
    out = []
    if d:
        for g in d.get("relatedGroup", {}).get("conceptGroup", []):
            if g.get("tty") == "IN":
                for c in g.get("conceptProperties", []) or []:
                    out.append((c["rxcui"], c["name"]))
    return out

def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    open(PROG, "w").close()
    t0 = time.time()

    concepts, in_names = {}, {}
    for tty in ("IN", "PIN", "MIN", "BN"):
        rows = all_concepts(tty)
        for c in rows:
            concepts[c["rxcui"]] = (c["name"], tty)
            if tty == "IN": in_names[c["rxcui"]] = c["name"]
        log(f"allconcepts {tty}: {len(rows)}")

    # ---- Phase 1: valid ingredient-complete DB ----
    if os.path.exists(OUT): os.remove(OUT)
    con = sqlite3.connect(OUT)
    cur = con.cursor()
    cur.execute("CREATE TABLE drug_search(rxcui TEXT, name TEXT, name_lower TEXT, tty TEXT)")
    cur.executemany("INSERT INTO drug_search VALUES (?,?,?,?)",
                    [(rx, nm, nm.lower(), tty) for rx, (nm, tty) in concepts.items()])
    cur.execute("CREATE INDEX idx_name_lower ON drug_search(name_lower)")
    cur.execute("CREATE INDEX idx_rxcui ON drug_search(rxcui)")
    cur.execute("CREATE TABLE ingredient_map(drug_rxcui TEXT, ingredient_rxcui TEXT, ingredient_name TEXT)")
    cur.execute("CREATE INDEX idx_drug ON ingredient_map(drug_rxcui)")
    cur.execute("CREATE INDEX idx_ing ON ingredient_map(ingredient_rxcui)")
    # IN self-edges (zero API calls) — every generic is duplicate-detectable
    self_edges = [(rx, rx, nm) for rx, (nm, tty) in concepts.items() if tty == "IN"]
    cur.executemany("INSERT INTO ingredient_map VALUES (?,?,?)", self_edges)
    cur.execute("CREATE TABLE db_meta(key TEXT, value TEXT)")
    cur.executemany("INSERT INTO db_meta VALUES (?,?)", [
        ("source", "RxNorm via RxNav REST API (NLM) — full ingredient+brand universe"),
        ("built", date.today().isoformat()),
        ("drug_count", str(len(concepts))),
        ("bundled_subset", "false"),
        ("edge_status", "ingredients_complete; brand/combo edges enriching"),
    ])
    con.commit()
    log(f"Phase 1 DB written: {len(concepts)} drugs, {len(self_edges)} self-edges, {os.path.getsize(OUT)//1024}KB")

    # ---- Phase 2: enrich brand/combo/precise -> base IN edges, incrementally ----
    to_resolve = [rx for rx, (nm, tty) in concepts.items() if tty in ("PIN", "MIN", "BN")]
    log(f"enriching edges for {len(to_resolve)} PIN/MIN/BN ({WORKERS} workers)...")
    batch, done, total_edges = [], 0, 0
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        futs = {ex.submit(related_ins, rx): rx for rx in to_resolve}
        for fut in as_completed(futs):
            rx = futs[fut]
            try: ins = fut.result()
            except Exception: ins = []
            for in_rx, in_nm in ins:
                batch.append((rx, in_rx, in_names.get(in_rx, in_nm)))
            done += 1
            if len(batch) >= 1000:
                cur.executemany("INSERT INTO ingredient_map VALUES (?,?,?)", batch)
                con.commit(); total_edges += len(batch); batch = []
            if done % 2000 == 0:
                log(f"  edges: {done}/{len(to_resolve)} resolved, {total_edges} inserted ({time.time()-t0:.0f}s)")
    if batch:
        cur.executemany("INSERT INTO ingredient_map VALUES (?,?,?)", batch)
        con.commit(); total_edges += len(batch)

    # FTS + finalize meta
    try:
        cur.execute("CREATE VIRTUAL TABLE drug_fts USING fts5(name, rxcui UNINDEXED)")
        cur.execute("INSERT INTO drug_fts(name, rxcui) SELECT name, rxcui FROM drug_search")
        fts = True
    except sqlite3.OperationalError:
        fts = False
    cur.execute("UPDATE db_meta SET value=? WHERE key='edge_status'", (f"complete; {len(self_edges)+total_edges} edges",))
    cur.execute("INSERT INTO db_meta VALUES (?,?)", ("ingredient_edge_count", str(len(self_edges)+total_edges)))
    cur.execute("INSERT INTO db_meta VALUES (?,?)", ("fts5", "yes" if fts else "no"))
    con.commit(); con.close()
    log(f"DONE: {len(concepts)} drugs, {len(self_edges)+total_edges} edges, FTS={fts}, {os.path.getsize(OUT)//1024}KB, {time.time()-t0:.0f}s")
    open(os.path.join(OUT_DIR, "DONE"), "w").write("ok")

if __name__ == "__main__":
    main()
