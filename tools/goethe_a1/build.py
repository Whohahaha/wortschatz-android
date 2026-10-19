"""Builds the bundled Goethe A1 word book from the hand-written sources in this folder.

Usage: build.py <lexicon.tsv> <output .tsv>

The headwords follow the public Goethe-Zertifikat A1 (Start Deutsch 1) word list; the Chinese
meanings and all example sentences are written for this project. Plurals and verb forms not
given in the sources come from the Wiktionary lexicon (tools/lexicon/build_lexicon.py).

Source columns: pos, headword, meaning, example, example_zh, [plural or auxiliary]
  - nouns are written with their article ("der Tisch"); plural "-" means none
  - for verbs the last column overrides the perfect auxiliary (haben / sein)
  - separable verbs mark the prefix with "|" ("an|rufen")
"""
import glob
import os
import sys

UMLAUTS = str.maketrans({"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss"})
ARTICLES = {"der", "die", "das"}
COLUMNS = ["id", "level", "pos", "lemma", "gender", "plural", "meaning",
           "present3", "preterite", "participle", "aux", "example", "example_zh"]


def slug(text: str) -> str:
    s = text.lower().translate(UMLAUTS)
    return "".join(c if c.isalnum() else "-" for c in s).strip("-").replace("--", "-")


def load_lexicon(path: str):
    nouns, verbs = {}, {}
    for line in open(path, encoding="utf-8"):
        if line.startswith("#"):
            continue
        c = (line.rstrip("\n").split("\t") + [""] * 8)[:8]
        if c[1] == "n":
            nouns.setdefault((c[0], c[2]), c[3])
        elif c[1] == "v":
            verbs.setdefault(c[0], c[4:8])
    return nouns, verbs


def main() -> None:
    lexicon_path, out_path = sys.argv[1:3]
    nouns, verbs = load_lexicon(lexicon_path)
    here = os.path.dirname(os.path.abspath(__file__))
    rows, ids, notes = [], set(), []

    for path in sorted(glob.glob(os.path.join(here, "source_*.tsv"))):
        for n, line in enumerate(open(path, encoding="utf-8"), 1):
            if not line.strip() or line.startswith("#"):
                continue
            cells = line.rstrip("\n").split("\t")
            assert len(cells) in (5, 6), f"{path}:{n}: {len(cells)} columns"
            pos, head, meaning, example, example_zh = cells[:5]
            plural_override = cells[5] if len(cells) == 6 else None
            gender = plural = ""
            forms = ["", "", "", ""]
            lemma = head
            if pos == "n":
                article, lemma = head.split(" ", 1)
                assert article in ARTICLES, f"{path}:{n}: noun without article: {head}"
                gender = article
                if plural_override is not None:
                    plural = "" if plural_override == "-" else plural_override
                else:
                    plural = nouns.get((lemma, gender), "")
                    if not plural:
                        notes.append(f"no plural found: {head}")
            elif pos == "v" and " " not in head:
                found = verbs.get(head.replace("|", ""))
                if found and found[2]:
                    forms = list(found)
                    # For verbs the optional last column overrides the perfect auxiliary.
                    if plural_override:
                        forms[3] = plural_override
                else:
                    notes.append(f"no verb forms found: {head}")

            base = slug(lemma.replace("|", ""))
            entry_id = base if base not in ids else f"{base}-{pos}"
            assert entry_id not in ids, f"duplicate id {entry_id}"
            ids.add(entry_id)
            rows.append([entry_id, "A1", pos, lemma, gender, plural, meaning, *forms, example, example_zh])

    with open(out_path, "w", encoding="utf-8") as out:
        out.write("# title: 歌德 A1 词书\n")
        out.write("# 词头依据歌德学院 Goethe-Zertifikat A1（Start Deutsch 1）公开词表；中文释义和例句为本项目自编。\n")
        out.write("# 复数和动词变位来自 Wiktionary（经 kaikki.org 整理），CC BY-SA 4.0。\n")
        out.write("\t".join(COLUMNS) + "\n")
        for r in rows:
            out.write("\t".join(r) + "\n")
    print(f"{len(rows)} entries written to {out_path}")
    for note in notes:
        print("  " + note)


if __name__ == "__main__":
    main()
