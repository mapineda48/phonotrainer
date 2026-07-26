"""Etiquetado de fenómenos de connected speech sobre el diff real-vs-canónico.

Interpretamos cada desviación como fenómeno nativo a enseñar (inverso a MDD).
Entrada por palabra: {'word','start','end','canonical':[fonos],'real':[fonos]}
(fonos = {'phone','start','end',…} en tokens espeak/IPA con tiempos absolutos).
"""

from __future__ import annotations

from collections import Counter

from . import diff
from .canonical import clean_word
from .ipa_maps import (DIPHTHONGS, FIRST_ELEMENT, FLAP, GLOTTAL, is_consonant,
                       is_full_vowel, is_schwa_like, is_vowel)

LINK_MAX_GAP = 0.10  # s entre fin de consonante y vocal siguiente para linking

# MEJORA 2: diptongo → vocal simple cercana a su primer elemento.
# Umbral calibrado: positivos (a↔æ 0.17, o↔ɔ 0.08, e↔ɪ 0.25) vs negativos
# (a↔u 0.67, o↔i 0.50).
MONO_MAX_COST = 0.4

# MEJORA 3: cobertura mínima de fonos reales sobre la duración de la palabra;
# por debajo, la "elisión" probablemente es silencio/risas mal segmentados (TV).
WORD_ELISION_MIN_COVERAGE = 0.30

# MEJORA 4: referencia contra la que se define cada regla.
#   "aligned" = canónico espeak forzado en tiempo (comparte alfabeto con lo real);
#   "dict"    = forma de cita CMUdict — necesaria cuando espeak en-us YA incorpora
#               el proceso nativo (p.ej. canonical de "better" = bɛɾɚ, con flap:
#               un match ɾ↔ɾ solo es flapping si el diccionario tiene /t/ o /d/).
RULE_REFERENCE = {
    "vowel_reduction": "aligned",
    "monophthongization": "aligned",
    "t_deletion": "aligned",
    "glottalization": "aligned",
    "th_stopping": "aligned",
    "flapping": "dict",
    "palatalization": "aligned",
    "elision_syllable": "aligned",
    "word_elision": "aligned",
    "linking": "aligned",
    "h_dropping": "aligned",
    "contraction_lex": "aligned",
}

SYLLABIC = {"n̩", "l̩", "m̩", "ɹ̩"}

H_DROP_WORDS = {"he", "him", "her", "his", "have", "has", "had", "em"}

# Ruta (a): Whisper ya escribió la forma reducida.
CONTRACTIONS = {
    "wanna": "want to", "gonna": "going to", "gotta": "got to",
    "hafta": "have to", "hasta": "has to", "gotcha": "got you",
    "gotchu": "got you", "didja": "did you", "dontcha": "don't you",
    "whaddya": "what do you", "whatcha": "what are you", "lemme": "let me",
    "gimme": "give me", "kinda": "kind of", "sorta": "sort of",
    "outta": "out of", "lotta": "lot of", "shoulda": "should have",
    "coulda": "could have", "woulda": "would have", "musta": "must have",
    "mighta": "might have", "dunno": "don't know", "betcha": "bet you",
    "cuz": "because", "cause": "because", "ya": "you", "em": "them",
    "cmon": "come on", "c'mon": "come on", "imma": "i'm going to",
}

# Ruta (b): el texto trae la forma plena pero los fonos muestran la reducción.
EXPANSIONS_2 = {
    ("want", "to"): "wanna", ("going", "to"): "gonna", ("got", "to"): "gotta",
    ("have", "to"): "hafta", ("has", "to"): "hasta", ("got", "you"): "gotcha",
    ("did", "you"): "didja", ("don't", "you"): "dontcha", ("let", "me"): "lemme",
    ("give", "me"): "gimme", ("kind", "of"): "kinda", ("sort", "of"): "sorta",
    ("out", "of"): "outta", ("lot", "of"): "lotta", ("should", "have"): "shoulda",
    ("could", "have"): "coulda", ("would", "have"): "woulda",
    ("must", "have"): "musta", ("might", "have"): "mighta",
    ("don't", "know"): "dunno", ("bet", "you"): "betcha",
}
EXPANSIONS_3 = {
    ("what", "do", "you"): "whaddya", ("what", "are", "you"): "whatcha",
}

PALATAL_TRIGGER = {"t": "tʃ", "d": "dʒ", "s": "ʃ", "z": "ʒ"}
PALATAL_RESULTS = {"tʃ", "dʒ", "ʃ", "ʒ"}

# Etiquetas cuya presencia en el par de palabras avala una contracción léxica.
_REDUCTION_EVIDENCE = {"vowel_reduction", "t_deletion", "flapping",
                       "elision_syllable", "word_elision", "monophthongization"}


def _nuclei(phones: list[dict]) -> int:
    return sum(1 for p in phones if is_vowel(p["phone"]) or p["phone"] in SYLLABIC)


def _dict_has_td(word: dict) -> bool:
    """¿La forma de cita (CMUdict/g2p) contiene /t/ o /d/? (referencia "dict" del
    flapping). Sin información de diccionario se asume que sí (compatibilidad)."""
    arpa = word.get("dict_arpabet")
    if arpa is None:
        return True
    return any(p.rstrip("012") in {"T", "D"} for p in arpa)


def _word_elision(word: dict) -> bool:
    """MEJORA 3: realized vacío o cubriendo <30% de la palabra.

    Cobertura = EXTENSIÓN temporal (primer inicio → último fin) sobre la duración
    de la palabra, no suma de spans: los spans CTC greedy son picos de ~20-40 ms
    y la suma infra-estima sistemáticamente.
    """
    if not word["real"]:
        return True
    dur = word["end"] - word["start"]
    if dur <= 0:
        return False
    extent = word["real"][-1]["end"] - word["real"][0]["start"]
    return extent / dur < WORD_ELISION_MIN_COVERAGE


def _word_rules(word: dict, ops: list[dict]) -> set[str]:
    """Reglas intra-palabra sobre las operaciones del diff."""
    labels: set[str] = set()
    canonical = word["canonical"]
    n_canon = len(canonical)

    for k, op in enumerate(ops):
        c = op["canonical"]["phone"] if op["canonical"] else None
        r = op["real"]["phone"] if op["real"] else None

        if op["op"] == "sub":
            if is_full_vowel(c) and (is_schwa_like(r) or
                                     (r == "ɪ" and c not in {"i", "iː", "ɪ"}
                                      and c not in DIPHTHONGS)):
                labels.add("vowel_reduction")
            elif (c in DIPHTHONGS and is_vowel(r) and r not in DIPHTHONGS
                  and not is_schwa_like(r)
                  and diff.phone_cost(FIRST_ELEMENT[c], r) <= MONO_MAX_COST):
                # precedencia: la forma débil (real schwa) ya salió arriba como
                # vowel_reduction; aquí solo diptongos hacia su primer elemento
                labels.add("monophthongization")
            if (c == "ð" and r in {"d", "d̪"}) or (c == "θ" and r in {"t", "t̪"}):
                labels.add("th_stopping")
            if c in {"t", "d"} and r in GLOTTAL:
                labels.add("glottalization")

        if op["op"] in {"sub", "match"} and r in FLAP and c in {"t", "d"} | FLAP:
            if c in FLAP and not _dict_has_td(word):
                pass  # espeak pre-flapeado pero el diccionario no tiene t/d
            else:
                labels.add("flapping")

        if op["op"] == "del" and c in {"t", "d"}:
            # elidida en final de palabra (posición canónica final)
            canon_idx = canonical.index(op["canonical"])
            if canon_idx == n_canon - 1:
                labels.add("t_deletion")

    if _nuclei(word["real"]) < _nuclei(canonical) and any(
        op["op"] == "del" and is_vowel(op["canonical"]["phone"]) for op in ops
    ):
        labels.add("elision_syllable")

    return labels


def _boundary_rules(words: list[dict], idx: int) -> None:
    """Reglas entre words[idx] y words[idx+1] (linking, palatalization, h_dropping)."""
    w, nxt = words[idx], words[idx + 1]

    w_real_last = w["real"][-1]["phone"] if w["real"] else None
    nxt_real_first = nxt["real"][0]["phone"] if nxt["real"] else None
    nxt_first = nxt_real_first or (nxt["canonical"][0]["phone"] if nxt["canonical"] else None)

    if w_real_last and is_consonant(w_real_last) and nxt_first and is_vowel(nxt_first):
        t_end = w["real"][-1]["end"]
        t_next = nxt["real"][0]["start"] if nxt["real"] else nxt["start"]
        if t_next - t_end <= LINK_MAX_GAP:
            w["boundary_link_next"] = True
            w["phenomena_set"].add("linking")

    w_canon_last = w["canonical"][-1]["phone"] if w["canonical"] else None
    nxt_canon_first = nxt["canonical"][0]["phone"] if nxt["canonical"] else None
    if w_canon_last in PALATAL_TRIGGER and nxt_canon_first == "j":
        expected = PALATAL_TRIGGER[w_canon_last]
        boundary_real = {w_real_last, nxt_real_first}
        if expected in boundary_real or boundary_real & PALATAL_RESULTS:
            w["phenomena_set"].add("palatalization")


def _h_dropping(word: dict, ops: list[dict], is_utterance_initial: bool) -> None:
    if is_utterance_initial:
        return
    if clean_word(word["word"]) not in H_DROP_WORDS:
        return
    for op in ops:
        if op["canonical"] and op["canonical"]["phone"] == "h":
            if op["op"] == "del":
                word["phenomena_set"].add("h_dropping")
            return


def _contractions(words: list[dict], first_in_segment: bool) -> None:
    cleaned = [clean_word(w["word"]) for w in words]

    for w, cw in zip(words, cleaned):
        if cw in CONTRACTIONS:
            w["phenomena_set"].add("contraction_lex")
            w["lexical_form"] = cw
            w["lexical_expansion"] = CONTRACTIONS[cw]

    def evidence(group: list[dict]) -> bool:
        return any(g["phenomena_set"] & _REDUCTION_EVIDENCE for g in group)

    for i in range(len(words)):
        tri = tuple(cleaned[i:i + 3])
        if len(tri) == 3 and tri in EXPANSIONS_3 and evidence(words[i:i + 3]):
            words[i]["phenomena_set"].add("contraction_lex")
            words[i]["lexical_form"] = EXPANSIONS_3[tri]
            words[i]["lexical_expansion"] = " ".join(tri)
            continue
        bi = tuple(cleaned[i:i + 2])
        if len(bi) == 2 and bi in EXPANSIONS_2 and evidence(words[i:i + 2]):
            words[i]["phenomena_set"].add("contraction_lex")
            words[i]["lexical_form"] = EXPANSIONS_2[bi]
            words[i]["lexical_expansion"] = " ".join(bi)


def detect(words: list[dict], first_in_segment: bool = True) -> list[dict]:
    """Anota cada palabra con ops, phenomena, boundary_link_next y lexical_form."""
    out = []
    for w in words:
        w = dict(w)
        w["ops"] = diff.align_word(w["real"], w["canonical"])
        w["phenomena_set"] = _word_rules(w, w["ops"])
        w["boundary_link_next"] = False
        w.setdefault("lexical_form", None)
        if _word_elision(w):
            w["phenomena_set"].discard("elision_syllable")
            w["phenomena_set"].add("word_elision")
            w["low_confidence"] = True
        else:
            w["low_confidence"] = False
        out.append(w)

    for i in range(len(out) - 1):
        _boundary_rules(out, i)
    for i, w in enumerate(out):
        _h_dropping(w, w["ops"], is_utterance_initial=(first_in_segment and i == 0))
    _contractions(out, first_in_segment)

    for w in out:
        w["phenomena"] = sorted(w.pop("phenomena_set"))
    return out


def count_phenomena(segments: list[dict]) -> Counter:
    counts: Counter = Counter()
    for seg in segments:
        for w in seg["words"]:
            counts.update(w.get("phenomena", []))
    return counts
