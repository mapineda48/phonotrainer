"""Labeling of connected-speech phenomena over the real-vs-canonical diff.

Every deviation is read as a native phenomenon worth teaching (the inverse of MDD).
Per-word input: {'word','start','end','canonical':[phones],'real':[phones]}
(phones = {'phone','start','end',…} as IPA tokens of ipa_maps.ENGLISH_INVENTORY with
absolute times, whichever engine produced them).
"""

from __future__ import annotations

from collections import Counter

from . import diff
from .canonical import STRONG_CITATION, clean_word
from .ipa_maps import (DIPHTHONGS, FIRST_ELEMENT, FLAP, GLOTTAL, LABIAL,
                       PLACE_ASSIMILATION, SYLLABIC, UNRELEASED, VELAR, is_consonant,
                       is_schwa_like, is_vowel, reduces_vowel)

# Generation of these rules, recorded in every analysis (meta.rules_version): the
# labels are frozen into analysis.json, so the corpus must know which rules made
# them before it pools their figures. Bump it when a rule changes what it labels.
#   1 (unrecorded): the espeak-era rules, before the TIMIT engine
#   2: timit61 citation canonical, t_unreleased, nt_reduction, place_assimilation,
#      function_elision, link types; no labels on unheard words
RULES_VERSION = 2

LINK_MAX_GAP = 0.10  # s between the end of a consonant and the next vowel, for linking

# Linking (report §3G) and what carries it, in `boundary_link_type`: a consonant
# resyllabified onto the vowel (pick it up → pi-ki-dup), the /r/ of a rhotic
# ending (far away → fa-ra-way), or the glide a vowel ends in (go on → go‿won,
# he is → he‿yis). A glottal onset ([ʔ] before the vowel) or an unreleased final
# stop is the opposite of a link: the two words are kept apart.
RHOTIC = frozenset({"ɹ", "ɚ", "ɝ", "ɑːɹ", "ɔːɹ", "oːɹ", "ɛɹ", "ɪɹ", "ʊɹ", "aɪɚ"})
GLIDE_W_VOWELS = frozenset({"u", "uː", "ʊ", "oʊ", "aʊ"})
GLIDE_J_VOWELS = frozenset({"i", "iː", "ɪ", "eɪ", "aɪ", "ɔɪ"})
_GLIDE_TYPE = {"w": "glide_w", "j": "glide_j"}
LINK_TYPES = ("consonant", "r", "glide_w", "glide_j")
# Onsets that drop in unstressed function words and leave the vowel to link to:
# tell him → te‿lim, get them → ge‿dem (h_dropping, function_elision).
_DROPPABLE_ONSETS = frozenset({"h", "ð"})
_SENTENCE_END = (".", "?", "!")

# /nt/ (and /nd/) before a vowel: the stop goes and the n stays, or both fuse into
# a nasal flap — twenty → twɛni, winter → wɪɾ̃ɚ, want it → wɑɾ̃ɪt (report §5). The
# espeak model has no ɾ̃ and writes the nasal flap as [nɾ] or [ɾ]. Either way it is
# nt_reduction and NOT flapping, which stays the oral flap of a /t, d/ after a
# vowel (water, city): one label per /t/.
_NT_NASAL = frozenset({"n", "ɾ̃"})

# A function word losing a consonant other than its h (h_dropping) and a final
# t/d (t_deletion / t_unreleased): of → ə, have → ə, them → əm, would → əd — the
# weak forms of the report's §2 table taken one step further (§3E: lotsa, kinda).
FUNCTION_ELISION = {
    "of": frozenset({"v"}), "have": frozenset({"v"}), "them": frozenset({"ð"}),
    "would": frozenset({"w"}), "could've": frozenset({"v"}),
    "should've": frozenset({"v"}), "would've": frozenset({"v"}),
    "must've": frozenset({"v"}), "might've": frozenset({"v"}),
}

# IMPROVEMENT 2: diphthong → simple vowel close to its first element.
# Calibrated threshold: positives (a↔æ 0.17, o↔ɔ 0.08, e↔ɪ 0.25) vs negatives
# (a↔u 0.67, o↔i 0.50).
MONO_MAX_COST = 0.4

# IMPROVEMENT 3: minimum coverage of real phones over the word's duration; below it
# the "elision" is most likely silence/laughter that was mis-segmented (TV audio).
WORD_ELISION_MIN_COVERAGE = 0.30

# IMPROVEMENT 4: the reference each rule is defined against.
#   "aligned" = espeak canonical forced in time (shares its alphabet with the real
#               phones);
#   "dict"    = CMUdict citation form — needed when espeak en-us ALREADY bakes in the
#               native process (e.g. the canonical of "better" is bɛɾɚ, already
#               flapped: a ɾ↔ɾ match is only flapping if the dictionary has /t/ or
#               /d/). Same story for glottalization (button → bʌʔn̩) and syncope
#               (camera → kæmɹə, different → dɪfɹənt, every → ɛvɹi).
# With the default timit61 engine the aligned canonical IS the CMUdict citation
# form, so both references coincide and the "dict" vetoes never fire; the split
# only matters for the espeak engine.
RULE_REFERENCE = {
    "vowel_reduction": "aligned",
    "monophthongization": "aligned",
    "t_deletion": "aligned",
    "t_unreleased": "aligned",
    "glottalization": "dict",
    "th_stopping": "aligned",
    "flapping": "dict",
    "palatalization": "aligned",
    "elision_syllable": "dict",
    "word_elision": "aligned",
    "linking": "aligned",
    "h_dropping": "aligned",
    "contraction_lex": "aligned",
    "place_assimilation": "aligned",
    "nt_reduction": "aligned",
    "function_elision": "aligned",
}

# Single espeak tokens that hold two syllables: sci-ence saɪəns, fi-re faɪɚ, i-de-a
# aɪdiə. Counting them as one nucleus made espeak look pre-syncopated against
# CMUdict. iə is only one syllable before ɹ (era iəɹə, theory θiəɹi); elsewhere
# it is mostly two (area, audience, various, museum, material), and undercounting
# those would turn every "idea" into a lost syllable.
MULTI_NUCLEUS = {"aɪə": 2, "aɪɚ": 2, "iə": 2}

# Sonorants that turn syllabic after an obstruent and before a consonant (or the
# end of the word): espeak writes didn't as dɪdnt, not dɪdn̩t, and the recognizer
# follows it — without this, every "didn't" and "people" [piːpl] read as a lost
# syllable.
_SYLLABIC_SONORANTS = {"n", "l", "m"}
_SONORANTS = {"n", "l", "m", "ŋ", "ɹ", "w", "j"}

H_DROP_WORDS = {"he", "him", "her", "his", "have", "has", "had"}

# Path (a): Whisper already wrote the reduced form.
CONTRACTIONS = {
    "wanna": "want to", "gonna": "going to", "gotta": "got to",
    "hafta": "have to", "hasta": "has to", "gotcha": "got you",
    "gotchu": "got you", "didja": "did you", "dontcha": "don't you",
    "whaddya": "what do you", "whatcha": "what are you", "lemme": "let me",
    "gimme": "give me", "kinda": "kind of", "sorta": "sort of",
    "outta": "out of", "lotta": "lot of", "shoulda": "should have",
    "coulda": "could have", "woulda": "would have", "musta": "must have",
    "mighta": "might have", "dunno": "don't know", "betcha": "bet you",
    # clean_word keeps apostrophes: Whisper's "'em" arrives as "'em", and a bare
    # "cause" is just as often the noun ("the cause of it")
    "cuz": "because", "'cuz": "because", "'cause": "because", "ya": "you",
    "'em": "them", "cmon": "come on", "c'mon": "come on", "imma": "i'm going to",
    # the rest of the report's §1 table, with the spellings Whisper uses for them
    "oughta": "ought to", "useta": "used to", "usta": "used to",
    "supposta": "supposed to", "sposta": "supposed to", "s'posta": "supposed to",
    "tryna": "trying to", "finna": "fixing to", "cuppa": "cup of",
    "lotsa": "lots of", "doncha": "don't you", "wouldja": "would you",
    "couldja": "could you", "didya": "did you", "whaddaya": "what do you",
    "watcha": "what are you", "whatchu": "what are you",
}

# Path (b): the text carries the full form but the phones show the reduction.
EXPANSIONS_2 = {
    ("want", "to"): "wanna", ("going", "to"): "gonna", ("got", "to"): "gotta",
    ("have", "to"): "hafta", ("has", "to"): "hasta", ("got", "you"): "gotcha",
    ("did", "you"): "didja", ("don't", "you"): "dontcha", ("let", "me"): "lemme",
    ("give", "me"): "gimme", ("kind", "of"): "kinda", ("sort", "of"): "sorta",
    ("out", "of"): "outta", ("lot", "of"): "lotta", ("should", "have"): "shoulda",
    ("could", "have"): "coulda", ("would", "have"): "woulda",
    ("must", "have"): "musta", ("might", "have"): "mighta",
    ("don't", "know"): "dunno", ("bet", "you"): "betcha",
    ("ought", "to"): "oughta", ("used", "to"): "useta",
    ("supposed", "to"): "supposta", ("trying", "to"): "tryna",
    ("fixing", "to"): "finna", ("cup", "of"): "cuppa", ("lots", "of"): "lotsa",
    ("would", "you"): "wouldja", ("could", "you"): "couldja",
}
EXPANSIONS_3 = {
    ("what", "do", "you"): "whaddya", ("what", "are", "you"): "whatcha",
}

# "gonna" is only the future + a verb: "going to" followed by a determiner, a
# possessive or an object pronoun is motion toward a place, and it never contracts
# (*I'm gonna the store).
GONNA_BLOCKERS = frozenset({
    "the", "a", "an", "this", "that", "these", "those", "my", "your", "his", "her",
    "its", "our", "their", "some", "any", "every", "each", "no", "another",
    "me", "him", "us", "them",
})
_FIRST_PERSON = {"i", "i'm", "i'll", "i'd", "i've"}

PALATAL_TRIGGER = {"t": "tʃ", "d": "dʒ", "s": "ʃ", "z": "ʒ"}
PALATAL_RESULTS = {"tʃ", "dʒ", "ʃ", "ʒ"}

_JUNCTION_STOPS = {"t", "d", "ʔ", "t̚", "d̚"}
_UNRELEASED_TD = {"t̚", "d̚"}
_PALATAL_JUNCTION = {"tʃ", "dʒ"}


def _nuclei(phones: list[dict]) -> int:
    seq = [p["phone"] for p in phones]
    count = 0
    for k, ph in enumerate(seq):
        if ph == "iə" and seq[k + 1:k + 2] == ["ɹ"]:
            count += 1
        elif is_vowel(ph) or ph in SYLLABIC:
            count += MULTI_NUCLEUS.get(ph, 1)
        elif ph in _SYLLABIC_SONORANTS and _is_syllabic_sonorant(seq, k):
            count += 1
    return count


def _is_syllabic_sonorant(seq: list[str], k: int) -> bool:
    """n/l/m after an obstruent and before a consonant or the word end (didn't
    dɪdnt, people piːpl, button bʌtn)."""
    if k == 0:
        return False
    prev = seq[k - 1]
    nxt = seq[k + 1] if k + 1 < len(seq) else None
    if nxt is not None and is_vowel(nxt):
        return False
    return is_consonant(prev) and prev not in _SONORANTS and prev not in SYLLABIC


def _dict_syllables(word: dict) -> int | None:
    """Syllables of the citation form (CMUdict stress digits); None without it.
    An out-of-vocabulary word's "citation form" is a g2p guess (muy, poopsikins),
    not a reference: the aligned canonical is used instead."""
    arpa = word.get("dict_arpabet")
    if not arpa or word.get("oov"):
        return None
    return sum(1 for p in arpa if p[-1:].isdigit())


def _dict_has_td(word: dict) -> bool:
    """Does the citation form (CMUdict/g2p) contain /t/ or /d/? (this is flapping's
    "dict" reference). With no dictionary information — or only a g2p guess — we
    assume it does, and the veto stays out of the way."""
    arpa = word.get("dict_arpabet")
    if arpa is None or word.get("oov"):
        return True
    return any(p.rstrip("012") in {"T", "D"} for p in arpa)


def _canon_ops(ops: list[dict]) -> list[dict]:
    """The op of each canonical phone, in canonical order: every canonical phone
    is in exactly one match, sub or del."""
    return [op for op in ops if op["canonical"] is not None]


def _nt_sites(canonical: list[dict]) -> list[int]:
    """Indices k of the n of a word-internal /nt/ or /nd/ before a vowel."""
    seq = [p["phone"] for p in canonical]
    return [k for k in range(len(seq) - 2)
            if seq[k] == "n" and seq[k + 1] in {"t", "d"} and is_vowel(seq[k + 2])]


def _nt_reduced(op_n: dict, op_t: dict) -> bool:
    """Did this /nt/ lose its stop (the n alone) or fuse into a nasal flap?"""
    rn = op_n["real"]["phone"] if op_n["real"] else None
    rt = op_t["real"]["phone"] if op_t["real"] else None
    if rt is None:
        return rn in _NT_NASAL
    if rt in FLAP:
        return rn is None or rn in _NT_NASAL
    return False


def _word_elision(word: dict) -> bool:
    """IMPROVEMENT 3: realized is empty, or covers <30% of the word.

    Coverage = temporal EXTENT (first start → last end) over the word's duration,
    not the sum of the spans: greedy CTC spans are ~20-40 ms spikes, so summing them
    underestimates coverage systematically.
    """
    if not word["real"]:
        return True
    dur = word["end"] - word["start"]
    if dur <= 0:
        return False
    extent = word["real"][-1]["end"] - word["real"][0]["start"]
    return extent / dur < WORD_ELISION_MIN_COVERAGE


def _word_rules(word: dict, ops: list[dict], narrow: bool = False) -> set[str]:
    """Intra-word rules over the diff operations.

    `narrow`: the recognizer writes unreleased stops (the timit61 engine), so a /t/
    with neither closure nor burst really is gone, and an unreleased one is seen.
    """
    labels: set[str] = set()
    canonical = word["canonical"]
    n_canon = len(canonical)
    weak_word = clean_word(word["word"]) in STRONG_CITATION

    # /nt/ before a vowel is nt_reduction, never flapping (see _NT_NASAL); the
    # word-final /nt/ depends on the next word and is settled in detect()
    by_canon = _canon_ops(ops)
    nt_stops = set()
    for k in _nt_sites(canonical):
        nt_stops.add(id(canonical[k + 1]))
        # the vowel after it must be there: with it gone ("contest?" [kɑːnt]) the
        # aligner may as well have paired the /t/ with the last one
        if (_nt_reduced(by_canon[k], by_canon[k + 1])
                and by_canon[k + 2]["op"] != "del"):
            labels.add("nt_reduction")
    if (n_canon >= 2 and canonical[-2]["phone"] == "n"
            and canonical[-1]["phone"] in {"t", "d"}):
        nt_stops.add(id(canonical[-1]))

    for k, op in enumerate(ops):
        c = op["canonical"]["phone"] if op["canonical"] else None
        r = op["real"]["phone"] if op["real"] else None

        if op["op"] == "sub":
            if reduces_vowel(c, r, weak_form_word=weak_word):
                labels.add("vowel_reduction")
            elif (c in DIPHTHONGS and not weak_word and is_vowel(r)
                  and r not in DIPHTHONGS and not is_schwa_like(r)
                  and diff.phone_cost(FIRST_ELEMENT[c], r) <= MONO_MAX_COST):
                # precedence: the weak form (real schwa) has already been caught
                # above as vowel_reduction; only diphthongs collapsing toward their
                # first element land here. Not for a weak-form word (a → eɪ): its
                # plain [e]/[ɛ] is a clipped article, not the regional
                # /aɪ/ → [aː] this label teaches
                labels.add("monophthongization")
            if (c == "ð" and r in {"d", "d̪"}) or (c == "θ" and r in {"t", "t̪"}):
                labels.add("th_stopping")

        if op["op"] in {"sub", "match"} and r in FLAP and c in {"t", "d"} | FLAP:
            if c in FLAP and not _dict_has_td(word):
                pass  # espeak is pre-flapped but the dictionary has no t/d
            elif id(op["canonical"]) in nt_stops:
                pass  # the /t/ of an /nt/: nt_reduction's, not a flap
            else:
                labels.add("flapping")

        # "dict" reference, like flapping: espeak already writes button as bʌʔn̩,
        # so a ʔ↔ʔ match is glottalization when the citation form has the /t/
        if op["op"] in {"sub", "match"} and r in GLOTTAL and c in {"t", "d"} | GLOTTAL:
            if c in GLOTTAL and not _dict_has_td(word):
                pass  # a glottal stop of the word itself (uh-oh), not a /t/
            elif canonical.index(op["canonical"]) == 0:
                # a word-initial /t/ never glottalizes in American English: that ʔ
                # is the glottal onset of a neighboring vowel ("to [əʔ] eat"), which
                # the TIMIT engine writes with the same symbol
                pass
            else:
                labels.add("glottalization")

        if (narrow and op["op"] in {"sub", "match"} and c in {"t", "d"}
                and r in _UNRELEASED_TD
                and canonical.index(op["canonical"]) == n_canon - 1):
            # the closure is there, the burst never comes: that [ðæt̚]. Word-final
            # only, like the report's case — a closure mid-word before a nasal
            # (didn't [dɪd̚n̩t̚]) releases through the nose, a different thing
            labels.add("t_unreleased")

        if op["op"] == "del" and c in {"t", "d"}:
            # elided word-finally (final canonical position)
            canon_idx = canonical.index(op["canonical"])
            if canon_idx == n_canon - 1:
                prev = canonical[canon_idx - 1]["phone"] if canon_idx else None
                if narrow or prev is None or is_consonant(prev):
                    # cluster reduction (next day, old man, don't) — or, with a
                    # recognizer that writes [t̚], a /t/ with no closure at all
                    labels.add("t_deletion")
                else:
                    # after a vowel the /t/ is usually still there as a glottal
                    # closure or an unreleased [t̚]: silence to the espeak engine
                    labels.add("t_unreleased")

    # "dict" reference: espeak pre-syncopates camera/different/every, so the lost
    # syllable is counted against the citation form. The evidence is either a
    # vowel the aligner expected and nobody said, or a canonical that already
    # lacks it (and the speaker went along with espeak).
    real_n, canon_n = _nuclei(word["real"]), _nuclei(canonical)
    dict_n = _dict_syllables(word)
    reference = canon_n if dict_n is None else dict_n
    vowel_deleted = any(op["op"] == "del" and is_vowel(op["canonical"]["phone"])
                        for op in ops)
    if real_n < reference and (vowel_deleted or canon_n < reference):
        labels.add("elision_syllable")

    return labels


def _ends_sentence(word: dict) -> bool:
    return word["word"].rstrip().rstrip("\"'”’)").endswith(_SENTENCE_END)


def _link_type(w: dict, nxt: dict) -> str | None:
    """How `w` links onto `nxt` (one of LINK_TYPES), or None.

    The next word has to start with a vowel — as heard, or as expected when
    nothing was heard — within LINK_MAX_GAP, and no sentence may end in between.
    Evidence that the boundary moved counts too: the next word starts with a
    glide the recognizer heard (go [ɡoʊ] and [wən]), or with this word's own
    final consonant (an [æ] apple [næpl], door [dɔːɹ] over [ɹoʊvɚ]).
    """
    if not w["real"] or _ends_sentence(w):
        return None
    last = w["real"][-1]["phone"]
    t_end = w["real"][-1]["end"]
    first = nxt["real"][0]["phone"] if nxt["real"] else None
    t_next = nxt["real"][0]["start"] if nxt["real"] else nxt["start"]
    if t_next - t_end > LINK_MAX_GAP:
        return None
    canon_last = w["canonical"][-1]["phone"] if w["canonical"] else None
    canon_first = nxt["canonical"][0]["phone"] if nxt["canonical"] else None
    starts_with_vowel = is_vowel(first) if first else (
        canon_first is not None and is_vowel(canon_first))
    # a glide can only come out of a vowel the word really ends in: fill [fɪ] out
    # lost its l, it does not offer a j
    ends_in_vowel = (canon_last is not None and is_vowel(canon_last)
                     and canon_last not in RHOTIC)

    if starts_with_vowel:
        if canon_first is None or not (is_vowel(canon_first)
                                       or canon_first in _DROPPABLE_ONSETS):
            # the next word's own onset was bucketed into this one: didn't
            # [dɪnn̩ɹ] + realize [ilaɪz] is not a linking r
            return None
        if last in RHOTIC:
            # espeak writes some flaps as [ɹ] (got it [ɡɑːɹ ɪt]): only a word
            # that ends in an r links with one
            return "r" if canon_last in RHOTIC else "consonant"
        if last in GLOTTAL or last in UNRELEASED:
            return None  # a stop held (or glottal) before the vowel: no link
        if last in _GLIDE_TYPE and ends_in_vowel:
            return _glide(w["real"][-2]["phone"] if len(w["real"]) > 1 else None,
                          last, w)                 # the glide heard at the end of w
        if is_consonant(last):
            return "consonant"
        if not ends_in_vowel:
            return None
        if last in GLIDE_W_VOWELS:
            return "glide_w"
        if last in GLIDE_J_VOWELS:
            return "glide_j"
        return None                                # ə, ɑ, æ + vowel: a hiatus

    if first is None or canon_first is None or not is_vowel(canon_first):
        return None
    if first in _GLIDE_TYPE and is_vowel(last) and ends_in_vowel:
        return _glide(last, first, w)              # the glide heard at the start of nxt
    if first == canon_last and is_consonant(first) and first not in GLOTTAL:
        return "r" if first == "ɹ" else "consonant"
    if first in FLAP and canon_last in {"t", "d"}:
        return "consonant"                         # get it → ge‿ɾit
    if first == "ɹ" and canon_last in RHOTIC:
        return "r"
    return None


def _glide(vowel: str | None, glide: str, w: dict) -> str | None:
    """A heard w/j counts as the link only after a vowel that ends in it (a w after
    fɔːɹ is noise); "the" is the exception, since it turns into [ði] before a vowel."""
    if vowel is None or vowel in (GLIDE_W_VOWELS if glide == "w" else GLIDE_J_VOWELS):
        return _GLIDE_TYPE[glide]
    if glide == "j" and clean_word(w["word"]) == "the":
        return "glide_j"
    return None


def _boundary_rules(words: list[dict], idx: int) -> None:
    """Rules between words[idx] and words[idx+1]: linking, palatalization, place
    assimilation, and the word-final /nt/ before a vowel."""
    w, nxt = words[idx], words[idx + 1]

    w_real_last = w["real"][-1]["phone"] if w["real"] else None
    nxt_real_first = nxt["real"][0]["phone"] if nxt["real"] else None

    # On a word barely heard (word_elision) only positive evidence survives: a
    # palatal actually heard at the boundary (don't [dʒ] you). Linking, place and
    # the /nt/ read the word's own alignment, which there is mostly silence.
    link = None if w["low_confidence"] else _link_type(w, nxt)
    if link:
        w["boundary_link_next"] = True
        w["boundary_link_type"] = link
        w["phenomena_set"].add("linking")

    w_canon_last = w["canonical"][-1]["phone"] if w["canonical"] else None
    nxt_canon_first = nxt["canonical"][0]["phone"] if nxt["canonical"] else None
    if w_canon_last in PALATAL_TRIGGER and nxt_canon_first == "j":
        expected = PALATAL_TRIGGER[w_canon_last]
        boundary_real = {w_real_last, nxt_real_first}
        if expected in boundary_real or boundary_real & PALATAL_RESULTS:
            w["phenomena_set"].add("palatalization")

    if w["low_confidence"]:
        return
    if nxt_canon_first is not None and not _ends_sentence(w):
        place = ("labial" if nxt_canon_first in LABIAL
                 else "velar" if nxt_canon_first in VELAR else None)
        found = PLACE_ASSIMILATION.get((w_canon_last, place))
        by_canon = _canon_ops(w["ops"])
        # the final alveolar itself became labial/velar (a sub, not a phone of the
        # next word bucketed here after it: get [ɡɛt̚ m] + my [aɪ])
        if found and by_canon and by_canon[-1]["op"] == "sub" \
                and by_canon[-1]["real"]["phone"] in found:
            w["phenomena_set"].add("place_assimilation")

        moved = _moved_final_stop(w, nxt)
        if moved is not None:
            # the final /t/ was heard as the next word's onset (get it → ge‿tit,
            # geɾit): resyllabified, not deleted and not held unreleased
            w["phenomena_set"].discard("t_deletion")
            w["phenomena_set"].discard("t_unreleased")

        canon = w["canonical"]
        is_nt = (len(canon) >= 2 and canon[-2]["phone"] == "n"
                 and canon[-1]["phone"] in {"t", "d"})
        if moved in FLAP and not is_nt:
            w["phenomena_set"].add("flapping")
        if (is_nt and is_vowel(nxt_canon_first)
                and (moved is None or moved in FLAP)
                and _nt_reduced(by_canon[-2], by_canon[-1])):
            # want it → wɑɾ̃ɪt, and I → ænaɪ: before a vowel the lost /t/ is this,
            # not a cluster reduction. A [t] heard in the next word (wɑn‿tɪt) was
            # said, so that /nt/ did not reduce.
            w["phenomena_set"].add("nt_reduction")
            w["phenomena_set"].discard("t_deletion")


def _moved_final_stop(w: dict, nxt: dict) -> str | None:
    """The phone that realizes w's final /t/ or /d/ at the start of the next word,
    or None.

    The bucketing goes by midpoints, so a /t/ resyllabified onto the next vowel
    (get it → ge‿tit, geɾit) lands in the next word's window and w's own diff
    shows it deleted. It only counts when the next word starts with a vowel —
    otherwise that consonant is its own onset (get to [ɡɛ] [tə]). A glottal stop
    there is ambiguous (a glottalized /t/, or the vowel's glottal attack): it
    only keeps the /t/ from being called deleted.
    """
    canon = w["canonical"]
    if not canon or canon[-1]["phone"] not in {"t", "d"}:
        return None
    by_canon = _canon_ops(w["ops"])
    if not by_canon or by_canon[-1]["op"] != "del":
        return None
    if nxt["low_confidence"] or not nxt["real"] or not nxt["canonical"]:
        return None
    if not is_vowel(nxt["canonical"][0]["phone"]):
        return None
    first = nxt["real"][0]["phone"]
    if first == canon[-1]["phone"] or first in FLAP or first in GLOTTAL:
        return first
    return None


def _function_elision(words: list[dict], idx: int) -> None:
    w = words[idx]
    lost = FUNCTION_ELISION.get(clean_word(w["word"]))
    if not lost or not w["real"] or w["low_confidence"]:
        return
    canon = w["canonical"]
    prv = words[idx - 1] if idx > 0 else None
    nxt = words[idx + 1] if idx + 1 < len(words) else None
    for k, op in enumerate(_canon_ops(w["ops"])):
        c = op["canonical"]["phone"]
        if op["op"] != "del" or c not in lost:
            continue
        # a consonant heard at the edge of the neighbor was only bucketed there,
        # i.e. resyllabified (of it → ə vɪt: linking, not elision)
        if k == len(canon) - 1 and nxt and nxt["real"] and nxt["real"][0]["phone"] == c:
            continue
        if k == 0 and prv and prv["real"] and prv["real"][-1]["phone"] == c:
            continue
        w["phenomena_set"].add("function_elision")
        return


def _h_dropping(word: dict, ops: list[dict], is_utterance_initial: bool) -> None:
    if is_utterance_initial or word["low_confidence"]:
        return
    if clean_word(word["word"]) not in H_DROP_WORDS:
        return
    for op in ops:
        if op["canonical"] and op["canonical"]["phone"] == "h":
            # a glottal stop in the h's place is the vowel's glottal attack once
            # the h is gone (his [ʔɪz]): the same drop, not a glottalized /t/
            if op["op"] == "del" or (op["op"] == "sub" and op["real"]["phone"] in GLOTTAL):
                word["phenomena_set"].add("h_dropping")
            return


# --- Path (b) evidence: the reduced form's own signature ------------------------
# "Some reduction nearby" is not evidence: "to" comes out as [tə] almost always,
# so "going [ɡoʊɪŋ] to [tə]" passed for gonna and "have [hæv] to [tə]" for hafta
# (21 of the 55 phonetic contractions of the validation corpus had the full form
# audibly intact). Each form is judged at the junction of its two words instead.

def _junction(group: list[dict]) -> tuple[list[str], list[str], str] | None:
    """(junction, tail, last vowel) of the group's realized phones: the consonants
    between its last two vowels and those after the last one. None with fewer than
    two vowels — the second word was not heard well enough to judge. The phones are
    concatenated rather than read per word because the bucketing of a boundary
    phone is arbitrary (want [wɔntə] + to [])."""
    seq = [p["phone"] for w in group for p in w["real"]]
    vowels = [k for k, ph in enumerate(seq) if is_vowel(ph)]
    if len(vowels) < 2:
        return None
    return seq[vowels[-2] + 1:vowels[-1]], seq[vowels[-1] + 1:], seq[vowels[-1]]


def _only(junction: list[str], allowed: set[str]) -> bool:
    return bool(junction) and set(junction) <= allowed


def _sig_nasal(j, tail, v):         # wanna wɑnə, gonna ɡʌnə: the /t/ of "to" is gone
    return _only(j, {"n", "ŋ", "ɾ", "ɾ̃"})


def _sig_dunno(j, tail, v):         # dunno dəˈnoʊ: the /t/ of "don't" is gone
    return _only(j, {"n", "ɾ", "ɾ̃"}) and bool({"n", "ɾ̃"} & set(j))


def _sig_single_stop(j, tail, v):   # gotta ɡɑɾə: two /t/s fused into one flap or stop
    return len(j) == 1 and j[0] in FLAP | _JUNCTION_STOPS


def _sig_single_stop_of(j, tail, v):  # outta, lotta: and the /v/ of "of" is gone
    return _sig_single_stop(j, tail, v) and "v" not in tail


def _sig_devoiced(voiced: str, voiceless: str):  # hafta v→f, hasta z→s
    return lambda j, tail, v: voiceless in j and voiced not in j


def _sig_palatal(j, tail, v):       # gotcha, betcha, didja, dontcha
    return bool(set(j) & _PALATAL_JUNCTION)


def _sig_bilabial(j, tail, v):      # lemme, gimme: the /t/ or /v/ before /m/ is gone
    return _only(j, {"m"})


def _sig_of(j, tail, v):            # kinda, sorta: the /v/ of "of" is gone
    return "v" not in tail


def _sig_have(j, tail, v):          # shoulda ʃʊdə: "have" is a bare schwa, no h, no v
    return "v" not in tail and "h" not in j and is_schwa_like(v)


def _sig_st(j, tail, v):            # useta justə, supposta: z→s, the d+t fused into one
    stops = [ph for ph in j if ph in _JUNCTION_STOPS | FLAP]
    return "s" in j and "z" not in j and len(stops) <= 1


SIGNATURES = {
    "wanna": _sig_nasal, "gonna": _sig_nasal, "dunno": _sig_dunno,
    "gotta": _sig_single_stop, "outta": _sig_single_stop_of,
    "lotta": _sig_single_stop_of,
    "hafta": _sig_devoiced("v", "f"), "hasta": _sig_devoiced("z", "s"),
    "gotcha": _sig_palatal, "betcha": _sig_palatal, "didja": _sig_palatal,
    "dontcha": _sig_palatal, "lemme": _sig_bilabial, "gimme": _sig_bilabial,
    "kinda": _sig_of, "sorta": _sig_of,
    "shoulda": _sig_have, "coulda": _sig_have, "woulda": _sig_have,
    "musta": _sig_have, "mighta": _sig_have,
    "oughta": _sig_single_stop, "useta": _sig_st, "supposta": _sig_st,
    "tryna": _sig_nasal, "finna": _sig_nasal, "cuppa": _sig_of, "lotsa": _sig_of,
    "wouldja": _sig_palatal, "couldja": _sig_palatal,
}


def _has_signature(form: str, group: list[dict]) -> bool:
    if SIGNATURES.get(form) is _sig_palatal and "palatalization" in group[0]["phenomena_set"]:
        # the boundary rule already saw the tʃ/dʒ, even when the vowel of the first
        # word got lost (don't [dʒ] + you [uː])
        return True
    if form in {"whaddya", "whatcha"}:
        # what do you → wʌɾəjə / wʌdʒə, what are you → wʌtʃə: a flap, a palatal,
        # or a syllable gone
        real = [p for w in group for p in w["real"]]
        canon = [p for w in group for p in w["canonical"]]
        return (_nuclei(real) < _nuclei(canon)
                or bool({p["phone"] for p in real} & (FLAP | _PALATAL_JUNCTION)))
    found = _junction(group)
    return found is not None and SIGNATURES[form](*found)


def _gonna_blocked(after_to: dict | None, to_word: dict) -> bool:
    """Is "going to" followed by something that makes it motion, not future?"""
    if after_to is None or to_word["word"].rstrip().endswith(tuple(".?!,;:")):
        return False  # nothing after "to" in this sentence: nothing to judge
    raw = after_to["word"].strip("\"'“”‘’¿¡-")
    cw = clean_word(raw)
    if cw in GONNA_BLOCKERS:
        return True
    # a capitalized word mid-sentence is a name: going to Denver
    return bool(raw) and raw[0].isupper() and cw not in _FIRST_PERSON


def _contractions(words: list[dict], first_in_segment: bool) -> None:
    cleaned = [clean_word(w["word"]) for w in words]

    for w, cw in zip(words, cleaned):
        if cw in CONTRACTIONS:
            w["phenomena_set"].add("contraction_lex")
            w["lexical_form"] = cw
            w["lexical_expansion"] = CONTRACTIONS[cw]

    for i in range(len(words)):
        tri = tuple(cleaned[i:i + 3])
        if (len(tri) == 3 and tri in EXPANSIONS_3
                and _has_signature(EXPANSIONS_3[tri], words[i:i + 3])):
            words[i]["phenomena_set"].add("contraction_lex")
            words[i]["lexical_form"] = EXPANSIONS_3[tri]
            words[i]["lexical_expansion"] = " ".join(tri)
            continue
        bi = tuple(cleaned[i:i + 2])
        if len(bi) != 2 or bi not in EXPANSIONS_2:
            continue
        form = EXPANSIONS_2[bi]
        if form == "gonna" and _gonna_blocked(
                words[i + 2] if i + 2 < len(words) else None, words[i + 1]):
            continue
        if _has_signature(form, words[i:i + 2]):
            words[i]["phenomena_set"].add("contraction_lex")
            words[i]["lexical_form"] = form
            words[i]["lexical_expansion"] = " ".join(bi)


def detect(words: list[dict], first_in_segment: bool = True,
           narrow: bool = False) -> list[dict]:
    """Annotate every word with ops, phenomena, boundary_link_next and lexical_form.

    `narrow` is the engine's flag (see _word_rules): True for timit61.
    """
    out = []
    for w in words:
        w = dict(w)
        w["ops"] = diff.align_word(w["real"], w["canonical"])
        w["phenomena_set"] = _word_rules(w, w["ops"], narrow=narrow)
        w["boundary_link_next"] = False
        w["boundary_link_type"] = None
        w.setdefault("lexical_form", None)
        if _word_elision(w):
            # nothing (or almost nothing) was heard: a label read off this diff
            # would describe silence or laughter, not speech (a palatal heard at
            # the boundary is still judged in _boundary_rules)
            w["phenomena_set"] = {"word_elision"}
            w["low_confidence"] = True
        else:
            w["low_confidence"] = False
        out.append(w)

    for i in range(len(out) - 1):
        _boundary_rules(out, i)
    for i, w in enumerate(out):
        _h_dropping(w, w["ops"], is_utterance_initial=(first_in_segment and i == 0))
        _function_elision(out, i)
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
