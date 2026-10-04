"""Strong vs weak forms of function words, scored against the SAME emissions.

The phone recognizer was fine-tuned on espeak-generated labels, and its greedy output
mirrors espeak's sentence-level G2P: "of" comes out [ʌv], "you" [juː], "from" [fɹʌm]
(69 %, 78 % and 85 % of the tokens in the 2026-09-26 validation) while "to" and "a"
come out reduced — exactly where espeak reduces them. The per-frame argmax hides
whatever evidence the audio carries for the weak form.

Here every covered word is rewritten as each of its realizations and the whole
segment's canonical sequence is scored with the CTC loss over the emissions the
aligner already computed. The rest of the segment stays fixed, so the difference
between two realizations is the acoustic evidence for one over the other (a
likelihood ratio, the idea behind goodness-of-pronunciation scores). Scoring the
full segment instead of a cropped window means the neighbors' frames are explained
by the neighbors' own phones, and a shorter realization gains nothing for free:
the frames where the dropped phone was actually said still have to be paid for, as
blank or as another token.
"""

from __future__ import annotations

from .canonical import clean_word
from .ipa_maps import SYLLABIC, is_schwa_like, is_vowel, reduces_vowel

# Realizations per word in the model's espeak alphabet, the citation (strong) form
# first. Each dimension that varies independently (vowel, initial h, final
# consonant) is crossed, so that a realization never wins on the vowel just because
# it is the only one that drops the final /d/: "and" [æn] competes with [ən]. And
# every reduced realization has a full-vowel twin of the same length ("and" [n̩]
# ↔ [æ], "from" [fɚm] ↔ [fʌm]): CTC favors shorter sequences a little (a dropped
# onset consonant still wins 12 % of the time on content words), and the twins
# make that bias cancel out of the vowel decision instead of pushing it to "weak".
VARIANTS: dict[str, list[str]] = {
    "a": ["eɪ", "ə", "ɐ"],
    "an": ["æ n", "æ", "ə n", "ɐ n", "n̩"],
    "and": ["æ n d", "æ n", "æ", "ə n d", "ə n", "n̩"],
    "but": ["b ʌ t", "b ʌ", "b ə t", "b ə"],
    "or": ["ɔːɹ", "ɚ"],
    "of": ["ʌ v", "ʌ", "ə v", "ə", "ɐ"],
    "to": ["t uː", "t ʊ", "ɾ uː", "uː", "t ə", "ɾ ə", "ə"],
    "for": ["f ɔːɹ", "f ɚ", "f ə"],
    "from": ["f ɹ ʌ m", "f ʌ m", "f ɹ ə m", "f ɚ m"],
    "at": ["æ t", "æ", "ə t", "ə"],
    "as": ["æ z", "ə z"],
    "than": ["ð æ n", "ð æ", "ð ə n", "ð ɐ n", "ð n̩"],
    "can": ["k æ n", "k æ", "k ə n", "k n̩"],
    "could": ["k ʊ d", "k ʊ", "k ə d", "k ə"],
    "would": ["w ʊ d", "w ʊ", "w ə d", "w ə"],
    "should": ["ʃ ʊ d", "ʃ ʊ", "ʃ ə d", "ʃ ə"],
    "must": ["m ʌ s t", "m ʌ s", "m ə s t", "m ə s"],
    "have": ["h æ v", "æ v", "æ", "h ə v", "ə v", "ə", "v"],
    "has": ["h æ z", "æ z", "æ", "h ə z", "ə z", "z"],
    "had": ["h æ d", "æ d", "æ", "h ə d", "ə d", "d"],
    "was": ["w ʌ z", "w ɑː z", "w ə z"],
    "were": ["w ɜː", "w ɚ"],
    "do": ["d uː", "d ʊ", "d ə"],
    "does": ["d ʌ z", "d ə z"],
    "am": ["æ m", "æ", "ə m", "m"],
    "are": ["ɑːɹ", "ɚ"],
    "he": ["h iː", "iː", "h i", "i"],
    "him": ["h ɪ m", "ɪ m", "h ə m", "ə m"],
    "his": ["h ɪ z", "ɪ z", "h ə z", "ə z"],
    "her": ["h ɜː", "ɜː", "h ɚ", "ɚ"],
    "them": ["ð ɛ m", "ɛ m", "ð ə m", "ə m"],
    "us": ["ʌ s", "ə s"],
    "you": ["j uː", "j ʊ", "uː", "j ə", "ə"],
    "your": ["j ʊɹ", "j ɔːɹ", "ʊɹ", "j ɚ", "ɚ"],
    "some": ["s ʌ m", "s ə m"],
    "the": ["ð iː", "ð i", "ð ə", "ð ɐ"],
    "there": ["ð ɛɹ", "ð ɚ"],
}

H_WORDS = frozenset({"he", "him", "his", "her", "have", "has", "had"})

# Margins are log-likelihood ratios in nats. Calibrated on 9 clips (4,356 words)
# against controls where the change is NOT expected: monosyllabic content words
# with their vowel swapped for schwa (1,008 tokens: 2.2 % reach 2.0) and content
# words with their initial h dropped (59 tokens: 5.1 % reach 4.5, mostly words the
# recognizer garbled). The h threshold is higher because dropping a phone is also
# a shorter sequence, and a dropped onset consonant reaches 4.5 in 3 % of cases.
WEAK_MARGIN = 2.0
H_DROP_MARGIN = 4.5

_BATCH = 32  # hypotheses per ctc_loss call (bounds the alpha matrix in memory)


def _nucleus(tokens: list[str]) -> str | None:
    for t in tokens:
        if is_vowel(t) or t in SYLLABIC:
            return t
    return None


def is_reduced(tokens: list[str], strong: list[str]) -> bool:
    """Does this realization weaken the strong form's vowel?

    The rules' own criterion (ipa_maps.reduces_vowel; every word here is a
    weak-form word); a syllabic consonant or no vowel at all counts as the extreme
    case of the same process.
    """
    nuc = _nucleus(tokens)
    if nuc is None or nuc in SYLLABIC:
        return True
    return reduces_vowel(_nucleus(strong), nuc, weak_form_word=True)


def drops_h(tokens: list[str], strong: list[str]) -> bool:
    return strong[0] == "h" and (not tokens or tokens[0] != "h")


def _ctc_scores(log_probs, blank_id: int, hyps: list[list[int]]) -> list[float]:
    """log p(sequence | emissions) for each hypothesis (-inf when infeasible)."""
    import torch
    import torch.nn.functional as F

    T, C = log_probs.shape
    out: list[float] = []
    for i in range(0, len(hyps), _BATCH):
        batch = hyps[i:i + _BATCH]
        n = len(batch)
        lp = log_probs.float().unsqueeze(1).expand(T, n, C)
        targets = torch.tensor([t for h in batch for t in h], dtype=torch.long)
        loss = F.ctc_loss(lp, targets,
                          input_lengths=torch.full((n,), T, dtype=torch.long),
                          target_lengths=torch.tensor([len(h) for h in batch],
                                                      dtype=torch.long),
                          blank=blank_id, reduction="none", zero_infinity=False)
        out.extend(-float(x) for x in loss)
    return out


def _realization_ids(tokenizer, key: str) -> list[tuple[list[str], list[int]]]:
    unk = tokenizer.unk_token_id
    out = []
    for spaced in VARIANTS[key]:
        tokens = spaced.split()
        ids = [tokenizer.convert_tokens_to_ids(t) for t in tokens]
        if unk in ids or None in ids:
            continue
        out.append((tokens, ids))
    return out


def score_segment(tokenizer, blank_id: int, log_probs, words: list[str],
                  passes: int = 2) -> list[dict | None]:
    """Score the realizations of every covered word of one segment.

    `log_probs` are the segment's emissions [T, C] (the same ones the canonical
    alignment used), `words` its Whisper words. Returns, per word, None when the
    word is not covered (or cannot be scored), otherwise the `form` dict written to
    analysis.json. A second pass rescores each word with its neighbors set to their
    own best realization, so "of the" is judged against [əðə], not [ʌvðə].
    """
    from .align_canonical import canonical_phone_ids

    keys = [clean_word(w) for w in words]
    context = [list(canonical_phone_ids(tokenizer, w)) for w in words]
    covered = {i: _realization_ids(tokenizer, k) for i, k in enumerate(keys)
               if k in VARIANTS}
    covered = {i: r for i, r in covered.items() if len(r) > 1}
    if not covered:
        return [None] * len(words)

    scores: dict[int, list[float]] = {}
    for _ in range(max(1, passes)):
        hyps, owners = [], []
        for i, reals in covered.items():
            before = [t for ids in context[:i] for t in ids]
            after = [t for ids in context[i + 1:] for t in ids]
            for _tokens, ids in reals:
                hyps.append(before + ids + after)
                owners.append(i)
        flat = _ctc_scores(log_probs, blank_id, hyps)
        scores = {i: [] for i in covered}
        for i, s in zip(owners, flat):
            scores[i].append(s)
        for i, reals in covered.items():
            best = max(range(len(reals)), key=lambda k: scores[i][k])
            if scores[i][best] > float("-inf"):
                context[i] = reals[best][1]
        if len(covered) < 2:
            break

    out: list[dict | None] = [None] * len(words)
    for i, reals in covered.items():
        out[i] = _form(keys[i], reals, scores[i])
    return out


def _form(key: str, reals: list[tuple[list[str], list[int]]],
          scores: list[float]) -> dict | None:
    strong = reals[0][0]
    if scores[0] == float("-inf"):
        return None  # the segment cannot even hold the citation form
    best = max(range(len(reals)), key=lambda k: scores[k])

    def margin(pred) -> float | None:
        yes = [s for (tokens, _), s in zip(reals, scores) if pred(tokens)]
        no = [s for (tokens, _), s in zip(reals, scores) if not pred(tokens)]
        if not yes or not no:
            return None
        return round(max(yes) - max(no), 2)

    form = {
        "ipa": "".join(reals[best][0]),
        "strong_ipa": "".join(strong),
        "weak": is_reduced(reals[best][0], strong),
        "weak_margin": margin(lambda t: is_reduced(t, strong)),
        "scores": {"".join(tokens): round(s - scores[best], 2)
                   for (tokens, _), s in zip(reals, scores) if s > float("-inf")},
    }
    if key in H_WORDS:
        form["h_dropped"] = drops_h(reals[best][0], strong)
        form["h_drop_margin"] = margin(lambda t: drops_h(t, strong))
    return form


def apply(words: list[dict], forms: list[dict | None], first_in_segment: bool = True,
          weak_min: float = WEAK_MARGIN, h_min: float = H_DROP_MARGIN) -> None:
    """Attach `form` to every word and add the labels the greedy decoding missed.

    `words` are the analysis.json word dicts of one segment (after
    phenomena.detect). A label is added only when the aligned canonical form still
    had what went missing: a full vowel for vowel_reduction (espeak already writes
    "the" as [ðə], so a weak "the" is the reference, not a process) and an /h/ for
    h_dropping. `variant_labels` lists what came from here, so the UI can tell
    them from the labels read off the greedy phones.
    """
    for i, (w, form) in enumerate(zip(words, forms)):
        w["form"] = form
        w["variant_labels"] = []
        if form is None or w.get("low_confidence"):
            continue
        canon = [p[0] for p in w.get("canonical_aligned", [])]
        canon_nucleus = _nucleus(canon)
        added = []
        margin = form.get("weak_margin")
        if (margin is not None and margin >= weak_min
                and "vowel_reduction" not in w["phenomena"]
                and canon_nucleus is not None and canon_nucleus not in SYLLABIC
                and not is_schwa_like(canon_nucleus)):
            added.append("vowel_reduction")
        margin = form.get("h_drop_margin")
        if (margin is not None and margin >= h_min
                and not (first_in_segment and i == 0)
                and "h_dropping" not in w["phenomena"]
                and canon and canon[0] == "h"):
            added.append("h_dropping")
        if added:
            w["variant_labels"] = added
            w["phenomena"] = sorted(set(w["phenomena"]) | set(added))
