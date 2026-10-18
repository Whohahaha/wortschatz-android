"""Build the German grammar lexicon used to auto-complete imported word books.

Usage: build_lexicon.py <output .tsv.gz> [source url or local .jsonl]

Streams the kaikki.org extract of English Wiktionary's German entries (CC BY-SA 4.0 /
GFDL) and keeps, per lemma, what the card model needs:

    lemma  pos  gender  plural  present3  preterite  participle  auxiliary

pos is "n" or "v"; gender is der/die/das. Inflected-form entries ("Häuser", "ging") are
skipped. The script fails if a few well-known words come out wrong, so a format change
upstream breaks the build instead of shipping a broken lexicon.
"""
import gzip
import os
import json
import re
import sys
import urllib.request

SOURCE = "https://kaikki.org/dictionary/German/kaikki.org-dictionary-German.jsonl"
GENDERS = {"m": "der", "f": "die", "n": "das"}


def is_form_of(entry: dict) -> bool:
    senses = entry.get("senses") or []
    return bool(senses) and all("form_of" in s or "form-of" in (s.get("tags") or []) for s in senses)


def find_form(forms: list, required: set, excluded: set = frozenset()) -> str:
    for f in forms:
        tags = set(f.get("tags") or [])
        if required <= tags and not tags & excluded:
            form = f.get("form", "").strip()
            if form and form != "-":
                return form
    return ""


def noun_row(entry: dict):
    expansion = next((h.get("expansion", "") for h in entry.get("head_templates") or [] if h.get("name") == "de-noun"), "")
    word = entry["word"]
    if not expansion.startswith(word):
        return None
    m = re.match(r"([mfn])\b", expansion[len(word):].strip())
    if not m:
        return None
    gender = GENDERS[m.group(1)]
    plural = find_form(entry.get("forms") or [], {"nominative", "plural"}, {"diminutive"})
    return (entry["word"], "n", gender, plural, "", "", "", "")


def verb_row(entry: dict):
    forms = entry.get("forms") or []
    present3 = find_form(forms, {"present", "singular", "third-person"}, {"subjunctive", "subjunctive-i"})
    preterite = find_form(forms, {"preterite", "singular", "third-person", "indicative"})
    participle = find_form(forms, {"participle", "past"})
    auxiliaries = {f.get("form", "") for f in forms if "auxiliary" in (f.get("tags") or [])}
    # Verbs listed with both mostly take "sein" when intransitive (ich bin gefahren).
    auxiliary = "sein" if any("sein" in a for a in auxiliaries) else ("haben" if auxiliaries else "")
    if not participle:
        return None
    return (entry["word"], "v", "", "", present3, preterite, participle, auxiliary)


def main() -> None:
    out_path = sys.argv[1]
    rows = {}
    source = sys.argv[2] if len(sys.argv) > 2 else SOURCE
    opened = open(source, "rb") if not source.startswith("http") else urllib.request.urlopen(source)
    with opened as resp:
        for raw in resp:
            entry = json.loads(raw)
            pos = entry.get("pos")
            if pos not in ("noun", "verb") or is_form_of(entry):
                continue
            row = noun_row(entry) if pos == "noun" else verb_row(entry)
            if row:
                rows.setdefault((row[0], row[1]), row)

    expected = {
        ("Tisch", "n"): ("der", "Tische"),
        ("Mädchen", "n"): ("das", "Mädchen"),
        ("Zeitung", "n"): ("die", "Zeitungen"),
        ("fahren", "v"): ("fährt", "fuhr", "gefahren", "sein"),
        ("anrufen", "v"): ("ruft an", "rief an", "angerufen", "haben"),
        ("kaufen", "v"): ("kauft", "kaufte", "gekauft", "haben"),
    }
    problems = []
    for key, want in expected.items():
        row = rows.get(key)
        got = (row[2], row[3]) if row and key[1] == "n" else (row[4:8] if row else None)
        print(f"check {key}: {got}")
        if got != want:
            problems.append(f"{key}: expected {want}, got {got}")

    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    with gzip.open(out_path, "wt", encoding="utf-8") as out:
        out.write("# German grammar lexicon from Wiktionary via kaikki.org, CC BY-SA 4.0\n")
        for row in sorted(rows.values()):
            out.write("\t".join(c.replace("\t", " ") for c in row) + "\n")
    print(f"{len(rows)} lemmas written to {out_path}")
    if problems:
        sys.exit("lexicon check failed:\n" + "\n".join(problems))


if __name__ == "__main__":
    main()
