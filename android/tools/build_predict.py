#!/usr/bin/env python3
"""Build vgo word-prediction assets (unigram/bigram/trigram) from Leipzig sentence corpora.

Usage:
  build_predict.py --lang vi --out vi.txt CORPUS-sentences.txt [...]
  build_predict.py --lang en --heliboard main_en_US.combined --out en.txt CORPUS-sentences.txt [...]

Output format (UTF-8, one record per line, tab separated):
  #vgo-predict 1 lang=<lang>
  U <word> <count>                      word index = order of U lines (0-based); "<s>" = sentence start
  B <i> <j>:<c> <j>:<c> ...             next words after word i, best first
  T <i> <j> <k>:<c> <k>:<c> ...         next words after the pair (i, j), best first
Words are stored in their dominant casing ("I", "Hà"), counts are raw corpus counts.
"""
import argparse
import collections
import re
import sys
import unicodedata

VI_LETTERS = set("abcdefghijklmnopqrstuvwxyzđ"
                 "àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵ")
TONE_MARKS = "̣̀́̉̃"
TOKEN_RE = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*|[.!?…]+|\S")
SENT_END = re.compile(r"^[.!?…]+$")
TAG_RE = re.compile(r"<[^>]{1,20}>|\{[^}]{1,40}\}")


def old_style_tone(word: str) -> str:
    """hoà -> hòa, khoẻ -> khỏe, thuỷ -> thủy (vgo default oldStyleTone=true)."""
    if len(word) < 2:
        return word
    a, b = word[-2], word[-1]
    db = unicodedata.normalize("NFD", b)
    tone = next((t for t in TONE_MARKS if t in db), None)
    if tone is None:
        return word
    base_b = db.replace(tone, "")
    pair = a + base_b
    if pair not in ("oa", "oe", "uy"):
        return word
    prev = word[-3] if len(word) >= 3 else ""
    if prev and (prev in "aeiouyăâêôơư" or (pair == "uy" and prev == "q")):
        return word
    return word[:-2] + unicodedata.normalize("NFC", a + tone) + base_b


def tokenize(line: str, lang: str):
    """Yield words (original case) and None for hard breaks / "<s>" for sentence starts."""
    yield "<s>"
    for m in TOKEN_RE.finditer(line):
        t = m.group(0)
        if t[0].isalpha():
            yield t.replace("’", "'")
        elif SENT_END.match(t):
            yield "<s>"
        else:
            yield None


def valid_word(w: str, lang: str, allowed) -> bool:
    lw = w.lower()
    if lang == "vi":
        return len(lw) <= 12 and all(c in VI_LETTERS for c in lw)
    return lw in allowed


def load_heliboard(path):
    allowed, banned = set(), set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.startswith(" word="):
                continue
            fields = dict(kv.split("=", 1) for kv in line.strip().split(",") if "=" in kv)
            w = fields.get("word", "")
            if not w or " " in w:
                continue
            if "possibly_offensive" in fields or "not_a_word" in fields:
                banned.add(w.lower())
                continue
            allowed.add(w.lower())
    # "i" is flagged not_a_word but "I" is fine: keep any word with at least one clean spelling.
    return allowed, banned - allowed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", required=True, choices=["vi", "en"])
    ap.add_argument("--out", required=True)
    ap.add_argument("--heliboard")
    ap.add_argument("--max-words", type=int, default=60000)
    ap.add_argument("--min-uni", type=int, default=3)
    ap.add_argument("--min-bi", type=int, default=3)
    ap.add_argument("--min-tri", type=int, default=4)
    ap.add_argument("--top-bi", type=int, default=12)
    ap.add_argument("--top-tri", type=int, default=8)
    ap.add_argument("corpora", nargs="+")
    a = ap.parse_args()

    allowed = None
    if a.lang == "en":
        if not a.heliboard:
            sys.exit("--heliboard required for en")
        allowed, _ = load_heliboard(a.heliboard)
        print(f"heliboard allowed words: {len(allowed)}", file=sys.stderr)

    norm = (lambda w: old_style_tone(w)) if a.lang == "vi" else (lambda w: w)

    # Pass 1: unigram counts + casing votes (case votes exclude sentence-initial positions).
    uni = collections.Counter()
    case_votes = collections.defaultdict(collections.Counter)
    sentences = []
    for path in a.corpora:
        with open(path, encoding="utf-8", errors="replace") as f:
            for n, line in enumerate(f):
                text = line.split("\t", 1)[-1]
                text = TAG_RE.sub(" ", text)
                text = unicodedata.normalize("NFC", text.strip())
                toks = []
                prev = "<s>"
                for t in tokenize(text, a.lang):
                    if t is None or t == "<s>":
                        toks.append(t)
                        prev = t
                        continue
                    t = norm(t)
                    lw = t.lower()
                    if not valid_word(t, a.lang, allowed):
                        toks.append(None)
                        prev = None
                        continue
                    uni[lw] += 1
                    if prev != "<s>":
                        case_votes[lw][t] += 1
                    toks.append(lw)
                    prev = lw
                sentences.append(toks)
        print(f"read {path}: total sentences {len(sentences)}", file=sys.stderr)

    vocab = [w for w, c in uni.most_common(a.max_words) if c >= a.min_uni]
    idx = {w: i + 1 for i, w in enumerate(vocab)}  # 0 = <s>
    print(f"vocab {len(vocab)} (of {len(uni)} distinct)", file=sys.stderr)

    # Pass 2: bigram / trigram counts over vocab ids. None/OOV break the chain.
    bi = collections.Counter()
    tri = collections.Counter()
    V = len(vocab) + 1
    for toks in sentences:
        p2 = p1 = -1
        for t in toks:
            if t == "<s>":
                p2, p1 = -1, 0
                continue
            cur = idx.get(t, -1) if t is not None else -1
            if cur < 0:
                p2 = p1 = -1
                continue
            if p1 >= 0:
                bi[p1 * V + cur] += 1
                if p2 >= 0:
                    tri[(p2 * V + p1) * V + cur] += 1
            p2, p1 = p1, cur
    sentences.clear()
    print(f"bigrams {len(bi)}, trigrams {len(tri)}", file=sys.stderr)

    bi_by = collections.defaultdict(list)
    for k, c in bi.items():
        if c >= a.min_bi:
            bi_by[k // V].append((c, k % V))
    tri_by = collections.defaultdict(list)
    for k, c in tri.items():
        if c >= a.min_tri:
            tri_by[k // V].append((c, k % V))

    def display(w):
        votes = case_votes.get(w)
        if not votes:
            return w
        best, n = votes.most_common(1)[0]
        total = sum(votes.values())
        return best if best != w and n > 0.6 * total else w

    with open(a.out, "w", encoding="utf-8") as out:
        out.write(f"#vgo-predict 1 lang={a.lang}\n")
        out.write("U\t<s>\t0\n")
        for w in vocab:
            out.write(f"U\t{display(w)}\t{uni[w]}\n")
        nb = nt = 0
        for i in sorted(bi_by):
            lst = sorted(bi_by[i], reverse=True)[: a.top_bi]
            out.write("B\t%d\t%s\n" % (i, " ".join(f"{j}:{c}" for c, j in lst)))
            nb += len(lst)
        for k in sorted(tri_by):
            lst = sorted(tri_by[k], reverse=True)[: a.top_tri]
            if sum(c for c, _ in lst) < a.min_tri * 2:
                continue
            out.write("T\t%d\t%d\t%s\n" % (k // V, k % V, " ".join(f"{j}:{c}" for c, j in lst)))
            nt += len(lst)
    print(f"wrote {a.out}: {len(vocab)} words, {nb} bigram edges, {nt} trigram edges", file=sys.stderr)


if __name__ == "__main__":
    main()
