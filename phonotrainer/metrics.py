"""How much of what was heard is reduced: Johnson-type metrics, per analysis and
across the corpus, next to the figures the literature reports.

A single word that departs from its dictionary form teaches one phenomenon; the
share of words that do so teaches the scale of it — that reduction is the norm
of conversation, not an accident (Johnson 2004: over 60 % of words deviate).
Everything is computed from the words of analysis.json alone, so that analyses
made before this module existed can be measured too (the corpus backfills them).

Every figure is a ratio `{"count", "of", "pct"}` so that the corpus can add
analyses up token by token instead of averaging percentages.

The reference values come from the reduction report this module was written
against; they are rates over corpora of spontaneous American English, so a
scripted or animated clip is not expected to match them — the point is to put
the clip's number next to the conversational norm.
"""

from __future__ import annotations

import logging
import unicodedata

from . import diff, phenomena
from .canonical import clean_word
from .ipa_maps import is_vowel
from .lexicon import FUNCTION_WORDS
from .phones_real import DEFAULT_ENGINE, engine_of
from .prosody import rhythm_summary
from .variants import H_DROP_MARGIN, H_WORDS, VARIANTS, WEAK_MARGIN, is_reduced

logger = logging.getLogger("phonotrainer.metrics")

# Bump when a definition changes: the corpus recomputes stored metrics whose
# version is older (when it can still read their analysis.json).
#   2: the phone engine is recorded (the corpus pools per engine) and the
#      function-word list gained not/all/both/such, reflexives and prepositions.
#   3: the rules generation that labeled the analysis is recorded (`rules`), and
#      a pool that mixes generations says so (`mixed_rules`); the weak-form
#      criterion is the rules' own (ipa_maps.reduces_vowel).
#   4: words without a canonical (bare symbols, numeral fragments) are counted
#      apart (`words.no_canonical`) and add no labels and no vowels.
METRICS_VERSION = 4

# Figures from the report, each with where it comes from. `low`/`high` bound the
# reference (high None: "above low"; low == high: a point value); `cite` is the
# short form for tables, `source` the full one.
REFERENCE = {
    "deviate": {
        "low": 60.0, "high": None, "display": "> 60 %", "cite": "Johnson 2004",
        "source": "Johnson 2004, Massive reduction in conversational American "
                  "English (ViC/Buckeye, ~88,000 words)",
        "note": "words that depart from their citation form in at least one segment",
    },
    "segment_loss": {
        "low": 25.0, "high": 25.0, "display": "≈ 25 %", "cite": "Johnson 2004",
        "source": "Johnson 2004 (ViC/Buckeye)",
        "note": "words that lose at least one whole segment",
    },
    "syllable_loss": {
        "low": 5.9, "high": 5.9, "display": "≈ 5.9 %", "cite": "Johnson 2004",
        "source": "Johnson 2004 (ViC/Buckeye)",
        "note": "words that lose at least one syllable: about one word in 20",
    },
    "schwa_share": {
        "low": 20.0, "high": 25.0, "display": "20–25 %", "cite": "pedagogical estimates",
        "source": "pedagogical estimates quoted in the report (practiceme.app: ~1 in 5 "
                  "vowels; learnenglishsounds.com: ~25 % of syllables in connected speech)",
        "note": "schwa is the most frequent vowel of spoken English",
    },
    "function_words": {
        "low": 56.0, "high": 56.0, "display": "≈ 56 %", "cite": "Johnson 2004",
        "source": "Johnson 2004 (ViC: ~49,000 function vs ~38,500 content words)",
        "note": "about half of what is heard is a function word, reduced by default",
    },
    "flapping": {
        "low": 74.8, "high": 81.6, "display": "74.8–81.6 %",
        "cite": "Patterson & Connine 2001",
        "source": "Patterson & Connine 2001, Phonetica 58:254–275 (Switchboard: "
                  "Northern 74.8 %, North Midland 81.6 %); 99 % in read speech "
                  "(Zue & Laferriere 1979)",
        "note": "flapped /t, d/ in the flapping context (after a vowel or /r/, before "
                "an unstressed vowel)",
    },
    "glottal_prevocalic": {
        "low": 24.0, "high": 24.0, "display": "≈ 24 %", "cite": "Eddington & Channer 2010",
        "source": "Eddington & Channer 2010, American Speech 85.3:338–351 (Santa "
                  "Barbara Corpus: 262 of 1,101 word-final prevocalic /t/)",
        "note": "word-final /t/ before a vowel realized as a glottal stop; higher in "
                "young Western speakers",
    },
}

# What each ratio measures, in the words a learner reads (CLI, UI, report).
METRIC_LABELS = {
    "deviate": "words that differ from the citation form",
    "segment_loss": "words that lose a whole segment",
    "syllable_loss": "words that lose a syllable",
    "schwa_share": "reduced vowels (schwa) among all vowels",
    "function_words": "function words among all words",
    "weak_forms": "function words in their weak form",
    "flapping": "t/d flapped where flapping can happen",
    "glottal_before_syllabic_n": "glottal t before a syllabic n (button)",
    "glottal_prevocalic": "final t before a vowel as a glottal stop",
}

# Nuclei whose quality is the reduced one. Stress and length marks and
# diacritics are stripped first, so ə̥ (TIMIT ax-h), ɚ, ɨ (TIMIT ix) and espeak's
# əl all count; a syllabic consonant (n̩, l̩, m̩, ŋ̍) is a nucleus with no vowel at
# all — the extreme of the same reduction.
REDUCED_BASES = frozenset("əɐɚɘɵᵻɨ")
_SYLLABIC_MARKS = ("̩", "̍")   # n̩ (below), ŋ̍ (above)

# ARPAbet vowels come out of canonical.arpabet_to_ipa as these strings: one per
# syllable, which is what makes dict_ipa countable without reloading CMUdict.
_DICT_DIPHTHONGS = ("aɪ", "aʊ", "eɪ", "oʊ", "ɔɪ")
_DICT_AFFRICATES = ("tʃ", "dʒ")
_STRESS = "ˈˌ"


# --- small helpers ------------------------------------------------------------------
def ratio(count: int, of: int) -> dict:
    return {"count": int(count), "of": int(of),
            "pct": round(100.0 * count / of, 1) if of else None}


def _phones(rows: list) -> list[dict]:
    return [{"phone": p[0], "start": p[1], "end": p[2]} for p in rows or ()]


def _base(token: str) -> str:
    stripped = token.strip(_STRESS + "ː")
    decomposed = unicodedata.normalize("NFD", stripped)
    return "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn")


def _is_syllabic(token: str) -> bool:
    return (token in phenomena.SYLLABIC
            or any(mark in unicodedata.normalize("NFD", token) for mark in _SYLLABIC_MARKS))


def is_reduced_nucleus(token: str) -> bool:
    base = _base(token)
    return _is_syllabic(token) or (bool(base) and base[0] in REDUCED_BASES)


def nucleus_counts(phones: list[str]) -> tuple[int, int]:
    """(nuclei, reduced nuclei) of a phone sequence.

    Counts nuclei the way phenomena._nuclei does (two-syllable espeak tokens,
    syllabic sonorants written as plain n/l/m), so both modules agree on what a
    syllable is; reduced ones are those of REDUCED_BASES or syllabic.
    """
    nuclei = reduced = 0
    for k, ph in enumerate(phones):
        if ph == "iə" and phones[k + 1:k + 2] == ["ɹ"]:
            nuclei += 1
        elif is_vowel(ph) or _is_syllabic(ph):
            n = phenomena.MULTI_NUCLEUS.get(ph, 1)
            nuclei += n
            # aɪə = a full nucleus plus a reduced one
            reduced += (n - 1) if n > 1 else int(is_reduced_nucleus(ph))
        elif ph in phenomena._SYLLABIC_SONORANTS and phenomena._is_syllabic_sonorant(phones, k):
            nuclei += 1
            reduced += 1
    return nuclei, reduced


def dict_tokens(dict_ipa: str) -> list[tuple[str, bool]]:
    """dict_ipa ("wˈɔtɚ") → [(token, stressed)]: one vowel token per syllable,
    the stress mark attached to the vowel that follows it."""
    out: list[tuple[str, bool]] = []
    s = dict_ipa or ""
    i, stressed = 0, False
    while i < len(s):
        ch = s[i]
        if ch in _STRESS:
            stressed = True
            i += 1
            continue
        two = s[i:i + 2]
        token = two if two in _DICT_DIPHTHONGS or two in _DICT_AFFRICATES else ch
        # keep length and combining marks (uː, n̩) with their base
        while (i + len(token) < len(s)
               and (s[i + len(token)] == "ː"
                    or unicodedata.category(s[i + len(token)]) == "Mn")):
            token = s[i:i + len(token) + 1]
        i += len(token)
        if is_vowel(token):
            out.append((token, stressed))
            stressed = False
        else:
            out.append((token, False))
    return out


def dict_syllables(dict_ipa: str) -> int:
    return sum(1 for tok, _ in dict_tokens(dict_ipa) if is_vowel(tok) or _is_syllabic(tok))


# --- contexts from the citation form -------------------------------------------------
def _after_vowel(toks: list[tuple[str, bool]], k: int) -> bool:
    """Is position k preceded by a vowel, or by /r/ after a vowel (party, forty)?"""
    if k == 0:
        return False
    prev = toks[k - 1][0]
    if is_vowel(prev):
        return True
    return prev == "ɹ" and k >= 2 and is_vowel(toks[k - 2][0])


def _before_syllabic_n(toks: list[tuple[str, bool]], k: int) -> bool:
    """/t, d/ + unstressed ə + n at the end or before a consonant: button, certain,
    sudden — the glottal (or nasal-release) context, not the flapping one."""
    if k + 2 >= len(toks):
        return False
    vowel, stressed = toks[k + 1]
    if vowel != "ə" or stressed or toks[k + 2][0] != "n":
        return False
    after = toks[k + 3][0] if k + 3 < len(toks) else None
    return after is None or not is_vowel(after)


def flapping_context(dict_ipa: str) -> bool:
    """Does the citation form hold a word-internal flapping site: /t, d/ after a
    vowel (or V+r) and before an UNSTRESSED vowel (water, city, party — not
    atomic, attend, nor button)?"""
    toks = dict_tokens(dict_ipa)
    for k, (tok, _) in enumerate(toks):
        if tok not in ("t", "d") or k + 1 >= len(toks):
            continue
        nxt, stressed = toks[k + 1]
        if (is_vowel(nxt) and not stressed and _after_vowel(toks, k)
                and not _before_syllabic_n(toks, k)):
            return True
    return False


def syllabic_n_context(dict_ipa: str) -> bool:
    """/t/ before a syllabic n after a vowel, /r/ or /n/ (button, certain,
    mountain): where the report calls the glottal stop safe to produce."""
    toks = dict_tokens(dict_ipa)
    for k, (tok, _) in enumerate(toks):
        if tok != "t" or k == 0 or not _before_syllabic_n(toks, k):
            continue
        prev = toks[k - 1][0]
        if is_vowel(prev) or prev in ("ɹ", "n"):
            return True
    return False


def final_t_after_vowel(dict_ipa: str) -> bool:
    toks = dict_tokens(dict_ipa)
    return len(toks) >= 2 and toks[-1][0] == "t" and _after_vowel(toks, len(toks) - 1)


def starts_with_vowel(dict_ipa: str) -> bool:
    toks = dict_tokens(dict_ipa)
    return bool(toks) and is_vowel(toks[0][0])


# --- per analysis --------------------------------------------------------------------
def _weak_form(word: dict, canon: list[str], real: list[str]) -> bool | None:
    """Greedy weak form of a function word, or None if it has no strong vowel to
    weaken (espeak and CMUdict already write "the" as ðə)."""
    key = clean_word(word["word"])
    if key not in VARIANTS or not canon:
        return None
    nucleus = next((p for p in canon if is_vowel(p) or _is_syllabic(p)), None)
    if nucleus is None or is_reduced_nucleus(nucleus):
        return None
    drops_h = key in H_WORDS and canon[0] == "h" and (not real or real[0] != "h")
    return is_reduced(real, canon) or drops_h


def _variant_verdict(form: dict | None, weak_min: float, h_min: float) -> str | None:
    """'weak' / 'strong' / 'uncertain' from the variants.py rescoring."""
    if not form:
        return None
    weak = form.get("weak_margin")
    h = form.get("h_drop_margin")
    if (weak is not None and weak >= weak_min) or (h is not None and h >= h_min):
        return "weak"
    if weak is not None and weak <= -weak_min and (h is None or h <= -h_min):
        return "strong"
    return "uncertain"


def _final_t_outcome(word: dict) -> str:
    labels = set(word.get("phenomena") or ())
    if "glottalization" in labels:
        return "glottal"
    if "flapping" in labels:
        return "flap"
    if labels & {"t_unreleased", "t_deletion"}:
        return "unreleased"
    real = [p[0] for p in word.get("realized_aligned") or ()]
    if real and real[-1] == "t":
        return "released"
    if real and _base(real[-1]) == "t":        # t̚: no release burst
        return "unreleased"
    return "other"


FINAL_T_OUTCOMES = ("released", "flap", "glottal", "unreleased", "other")


def has_canonical(word: dict) -> bool:
    """Is there a canonical to measure this word against? Analyses made before
    `no_canonical` was recorded say it with an empty canonical_aligned."""
    return not word.get("no_canonical") and bool(word.get("canonical_aligned"))


def compute(analysis: dict) -> dict:
    """The metrics of one analysis (the `summary.metrics` of analysis.json).

    Words: `total` counts every word Whisper wrote; `analyzed` leaves out the
    low-confidence ones (no acoustic trace to compare) and those without a
    canonical form. Segment-level figures compare against the aligned canonical,
    which shares its alphabet with the realized phones; syllables are counted
    against the dictionary form, because espeak's canonical is sometimes already
    syncopated (camera kæmɹə) and a syllable count needs no shared alphabet.
    """
    meta = analysis.get("meta") or {}
    thresholds = meta.get("form_scoring") or {}
    weak_min = float(thresholds.get("weak_margin", WEAK_MARGIN))
    h_min = float(thresholds.get("h_drop_margin", H_DROP_MARGIN))

    total = low = analyzed = no_canon = 0
    deviate = seg_loss = 0
    syl_loss = syl_of = 0
    nuclei = reduced = 0
    function = 0
    weak = weak_of = 0
    variant = {"weak": 0, "strong": 0, "uncertain": 0}
    flap = flap_of = 0
    glottal_n = glottal_n_of = 0
    final_t = dict.fromkeys(FINAL_T_OUTCOMES, 0)
    labels: dict[str, int] = {}

    for segment in analysis.get("segments") or []:
        words = segment.get("words") or []
        for i, w in enumerate(words):
            total += 1
            if clean_word(w.get("word", "")) in FUNCTION_WORDS:
                function += 1
            if not has_canonical(w):
                # "9" or "%" in an analysis made before numerals were spoken out:
                # every phone heard there was an insertion against nothing
                no_canon += 1
                continue
            for label in w.get("phenomena") or ():
                labels[label] = labels.get(label, 0) + 1
            real_rows = w.get("realized_aligned") or []
            canon_rows = w.get("canonical_aligned") or []
            real = [p[0] for p in real_rows]
            canon = [p[0] for p in canon_rows]
            if w.get("low_confidence"):
                low += 1
                continue
            n, r = nucleus_counts(real)
            nuclei += n
            reduced += r
            analyzed += 1

            ops = diff.align_word(_phones(real_rows), _phones(canon_rows))
            deviate += any(op["op"] != "match" for op in ops)
            seg_loss += any(op["op"] == "del" for op in ops)

            dict_ipa = w.get("dict_ipa") or ""
            n_dict = dict_syllables(dict_ipa)
            # an out-of-vocabulary "dictionary" form is a g2p guess: no reference
            if n_dict and not w.get("oov"):
                syl_of += 1
                syl_loss += phenomena._nuclei(_phones(real_rows)) < n_dict

            verdict = _weak_form(w, canon, real)
            if verdict is not None:
                weak_of += 1
                weak += verdict
                v = _variant_verdict(w.get("form"), weak_min, h_min)
                if v is not None:
                    variant[v] += 1

            labs = set(w.get("phenomena") or ())
            if flapping_context(dict_ipa):
                flap_of += 1
                flap += "flapping" in labs
            if syllabic_n_context(dict_ipa):
                glottal_n_of += 1
                glottal_n += "glottalization" in labs

            nxt = words[i + 1] if i + 1 < len(words) else None
            if (nxt is not None and final_t_after_vowel(dict_ipa)
                    and starts_with_vowel(nxt.get("dict_ipa") or "")):
                final_t[_final_t_outcome(w)] += 1

    variant_of = sum(variant.values())
    final_of = sum(final_t.values())
    rhythms = [s.get("rhythm") for s in analysis.get("segments") or []]
    return {
        "version": METRICS_VERSION,
        # the canonical and the recognizer both depend on it (espeak ≈ 42 % of
        # words deviate, timit61 ≈ 72 % on the same clips): never pool across it
        "engine": engine_of(meta),
        # which rules labeled it: None for analyses made before this was recorded
        "rules": meta.get("rules_version"),
        "words": {"total": total, "analyzed": analyzed, "low_confidence": low,
                  "no_canonical": no_canon,
                  "low_confidence_pct": ratio(low, total)["pct"]},
        "deviate": ratio(deviate, analyzed),
        "segment_loss": ratio(seg_loss, analyzed),
        "syllable_loss": ratio(syl_loss, syl_of),
        "schwa_share": ratio(reduced, nuclei),
        "function_words": ratio(function, total),
        "weak_forms": {
            "greedy": ratio(weak, weak_of),
            "variant": ({**variant, **ratio(variant["weak"], variant_of)}
                        if variant_of else None),
        },
        "flapping": ratio(flap, flap_of),
        "glottal_before_syllabic_n": ratio(glottal_n, glottal_n_of),
        "glottal_prevocalic": ratio(final_t["glottal"], final_of),
        "final_t_prevocalic": {**final_t, "of": final_of},
        "labels": dict(sorted(labels.items(), key=lambda kv: -kv[1])),
        "labels_per_100_words": {k: round(100.0 * v / total, 2)
                                 for k, v in sorted(labels.items(), key=lambda kv: -kv[1])}
                                if total else {},
        "rhythm": rhythm_summary(rhythms),
    }


def safe_compute(analysis: dict) -> dict | None:
    """compute(), degrading to None: metrics must never block indexing."""
    try:
        return compute(analysis)
    except Exception:                                  # noqa: BLE001
        logger.warning("could not compute metrics", exc_info=True)
        return None


# --- corpus --------------------------------------------------------------------------
_RATIOS = ("deviate", "segment_loss", "syllable_loss", "schwa_share", "function_words",
           "flapping", "glottal_before_syllabic_n", "glottal_prevocalic")


def aggregate_by_engine(items: list[dict]) -> dict[str, dict]:
    """aggregate() per phone engine, the default engine first. The engines do
    not measure the same thing — a different recognizer and a different
    canonical — so their numbers are never added together."""
    groups: dict[str, list[dict]] = {}
    for m in items:
        if m:
            groups.setdefault(m.get("engine") or "espeak", []).append(m)
    order = sorted(groups, key=lambda e: (e != DEFAULT_ENGINE, e))
    return {engine: aggregate(groups[engine]) for engine in order}


def aggregate(items: list[dict]) -> dict | None:
    """Pool per-analysis metrics token by token (sums of counts, not a mean of
    percentages), in the same shape as compute(). Meant for analyses of ONE
    engine: see aggregate_by_engine()."""
    items = [m for m in items if m]
    if not items:
        return None

    def pooled(get) -> dict:
        parts = [get(m) for m in items]
        parts = [p for p in parts if p]
        return ratio(sum(p["count"] for p in parts), sum(p["of"] for p in parts))

    words = {k: sum(m["words"].get(k, 0) for m in items)
             for k in ("total", "analyzed", "low_confidence", "no_canonical")}
    words["low_confidence_pct"] = ratio(words["low_confidence"], words["total"])["pct"]
    variants = [m["weak_forms"]["variant"] for m in items if m["weak_forms"].get("variant")]
    variant = None
    if variants:
        counts = {k: sum(v[k] for v in variants) for k in ("weak", "strong", "uncertain")}
        variant = {**counts, **ratio(counts["weak"], sum(counts.values()))}
    final_t = {k: sum(m["final_t_prevocalic"][k] for m in items) for k in FINAL_T_OUTCOMES}
    final_t["of"] = sum(final_t.values())
    labels: dict[str, int] = {}
    for m in items:
        for k, v in m["labels"].items():
            labels[k] = labels.get(k, 0) + v
    labels = dict(sorted(labels.items(), key=lambda kv: -kv[1]))
    engines = {m.get("engine") for m in items}
    # label-based figures from different rules generations do not measure the
    # same thing either; the pool keeps them but says so
    rules = sorted({m.get("rules") for m in items}, key=lambda v: -1 if v is None else v)
    out = {
        "version": METRICS_VERSION,
        "engine": engines.pop() if len(engines) == 1 else None,
        "rules": rules,
        "mixed_rules": len(rules) > 1,
        "analyses": len(items),
        "words": words,
        **{name: pooled(lambda m, name=name: m.get(name)) for name in _RATIOS},
        "weak_forms": {"greedy": pooled(lambda m: m["weak_forms"]["greedy"]),
                       "variant": variant},
        "final_t_prevocalic": final_t,
        "labels": labels,
        "labels_per_100_words": ({k: round(100.0 * v / words["total"], 2)
                                  for k, v in labels.items()} if words["total"] else {}),
        "rhythm": rhythm_summary([m.get("rhythm") for m in items]),
    }
    return out
