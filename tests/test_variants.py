"""Strong/weak form scoring over synthetic CTC emissions (no model is loaded)."""

import glob
import json
import os

import pytest

from phonotrainer import variants

torch = pytest.importorskip("torch")

BLANK = "<pad>"
LEXICON = {
    "tell": "t ɛ l", "him": "h ɪ m", "of": "ʌ v", "cup": "k ʌ p", "tea": "t iː",
    "the": "ð ə", "cat": "k æ t", "sat": "s æ t",
}


class FakeTokenizer:
    """Just enough of Wav2Vec2PhonemeCTCTokenizer for canonical_phone_ids()."""

    def __init__(self):
        tokens = {t for v in variants.VARIANTS.values() for s in v for t in s.split()}
        tokens |= {t for s in LEXICON.values() for t in s.split()}
        self.vocab = {BLANK: 0, "<unk>": 1}
        for t in sorted(tokens):
            self.vocab[t] = len(self.vocab)
        self.unk_token_id = 1
        self.all_special_ids = [0, 1]

    def __call__(self, word):
        class Out:
            pass
        out = Out()
        out.input_ids = [self.vocab[t] for t in LEXICON.get(word, "").split()]
        return out

    def convert_tokens_to_ids(self, token):
        return self.vocab.get(token, self.unk_token_id)


TOK = FakeTokenizer()  # module-level: canonical_phone_ids caches by id(tokenizer)


def emissions(spoken: str, frames_per_phone: int = 3, peak: float = 0.9):
    """Log-probs [T, C] for a clearly spoken sequence: a spike per phone, blanks between."""
    C = len(TOK.vocab)
    rows = []

    def row(tid):
        p = torch.full((C,), (1 - peak) / (C - 1))
        p[tid] = peak
        return p

    rows.append(row(0))
    for t in spoken.split():
        for _ in range(frames_per_phone):
            rows.append(row(TOK.vocab[t]))
        rows.append(row(0))
    rows.append(row(0))
    return torch.log(torch.stack(rows))


def score(words, spoken):
    return variants.score_segment(TOK, 0, emissions(spoken), words)


def test_weak_and_h_less_form_wins_when_that_is_what_was_said():
    forms = score(["tell", "him"], "t ɛ l ə m")
    assert forms[0] is None  # "tell" is not covered
    him = forms[1]
    assert him["ipa"] == "əm" and him["strong_ipa"] == "hɪm"
    assert him["weak"] and him["weak_margin"] > variants.WEAK_MARGIN
    assert him["h_dropped"] and him["h_drop_margin"] > variants.H_DROP_MARGIN
    assert him["scores"]["əm"] == 0.0 and him["scores"]["hɪm"] < 0


def test_strong_form_wins_when_that_is_what_was_said():
    him = score(["tell", "him"], "t ɛ l h ɪ m")[1]
    assert him["ipa"] == "hɪm"
    assert not him["weak"] and him["weak_margin"] < 0
    assert not him["h_dropped"] and him["h_drop_margin"] < 0


def test_a_shorter_form_gets_nothing_for_free():
    # [ʌv] was said in full: dropping the /v/ must cost, not help
    of = score(["cup", "of", "tea"], "k ʌ p ʌ v t iː")[1]
    assert of["ipa"] == "ʌv"
    assert of["scores"]["ʌ"] < -variants.WEAK_MARGIN
    assert "h_dropped" not in of  # not an h-word


def test_neighbors_are_rescored_with_their_best_form():
    # both "of" and "the" are covered; the second pass must not be thrown off by
    # the first one's citation form
    forms = score(["cup", "of", "the"], "k ʌ p ə ð ə")
    assert forms[1]["ipa"] == "ə"
    assert forms[2]["ipa"] == "ðə"


def test_too_few_frames_gives_no_form():
    lp = emissions("k æ t")[:2]
    assert variants.score_segment(TOK, 0, lp, ["him"]) == [None]


def test_uncovered_segment_scores_nothing():
    assert score(["cat", "sat"], "k æ t s æ t") == [None, None]


def _word(text, canonical, phenomena=(), low_confidence=False):
    return {"word": text, "canonical_aligned": [[p, 0.0, 0.0] for p in canonical.split()],
            "phenomena": list(phenomena), "low_confidence": low_confidence}


def _form(weak_margin=None, h_drop_margin=None):
    form = {"ipa": "", "strong_ipa": "", "weak": False, "weak_margin": weak_margin,
            "scores": {}}
    if h_drop_margin is not None:
        form["h_dropped"] = h_drop_margin > 0
        form["h_drop_margin"] = h_drop_margin
    return form


def test_apply_adds_labels_and_records_where_they_came_from():
    words = [_word("tell", "t ɛ l"), _word("him", "h ɪ m"), _word("of", "ʌ v")]
    variants.apply(words, [None, _form(3.0, 6.0), _form(5.0)])
    assert words[0]["form"] is None and words[0]["variant_labels"] == []
    assert words[1]["phenomena"] == ["h_dropping", "vowel_reduction"]
    assert words[1]["variant_labels"] == ["vowel_reduction", "h_dropping"]
    assert words[2]["variant_labels"] == ["vowel_reduction"]


def test_apply_respects_thresholds_and_existing_labels():
    words = [_word("tell", "t ɛ l"), _word("him", "h ɪ m", phenomena=["vowel_reduction"]),
             _word("of", "ʌ v")]
    variants.apply(words, [None, _form(9.0, variants.H_DROP_MARGIN - 0.1),
                           _form(variants.WEAK_MARGIN - 0.1)])
    assert words[1]["phenomena"] == ["vowel_reduction"]
    assert words[1]["variant_labels"] == []  # the greedy phones already had it
    assert words[2]["phenomena"] == []


def test_apply_never_drops_an_utterance_initial_h():
    words = [_word("he", "h iː")]
    variants.apply(words, [_form(None, 9.0)], first_in_segment=True)
    assert "h_dropping" not in words[0]["phenomena"]
    variants.apply(words, [_form(None, 9.0)], first_in_segment=False)
    assert words[0]["variant_labels"] == ["h_dropping"]


def test_apply_skips_what_espeak_already_writes_weak_and_unreliable_words():
    # espeak's "the" is already [ðə]: a weak "the" is the reference, not a process
    words = [_word("the", "ð ə"), _word("of", "ʌ v", low_confidence=True)]
    variants.apply(words, [_form(9.0), _form(9.0)])
    assert words[0]["phenomena"] == [] and words[1]["phenomena"] == []
    assert words[1]["form"] is not None  # the score is still shown


def test_is_reduced_follows_the_vowel_reduction_rule():
    strong = "k æ n".split()
    assert variants.is_reduced("k ə n".split(), strong)
    assert variants.is_reduced("k n̩".split(), strong)
    assert not variants.is_reduced("k æ n".split(), strong)
    assert not variants.is_reduced("h i".split(), "h iː".split())  # he → [hi] is not a schwa


def test_every_reduced_realization_has_a_full_twin_of_the_same_length():
    # CTC slightly favors shorter sequences: the twins keep that out of the vowel margin
    for key, reals in variants.VARIANTS.items():
        strong = reals[0].split()
        full = {len(r.split()) for r in reals if not variants.is_reduced(r.split(), strong)}
        for r in reals:
            if variants.is_reduced(r.split(), strong):
                assert len(r.split()) in full, (key, r)


def _cached_vocab():
    pattern = os.path.expanduser(
        "~/.cache/huggingface/hub/models--facebook--wav2vec2-lv-60-espeak-cv-ft/"
        "snapshots/*/vocab.json")
    paths = glob.glob(pattern)
    return paths[0] if paths else None


@pytest.mark.skipif(_cached_vocab() is None, reason="wav2vec2-espeak vocab not cached")
def test_every_variant_token_exists_in_the_model_vocabulary():
    vocab = json.load(open(_cached_vocab(), encoding="utf-8"))
    if isinstance(next(iter(vocab.values())), dict):
        vocab = next(iter(vocab.values()))
    missing = {t for v in variants.VARIANTS.values() for s in v for t in s.split()
               if t not in vocab}
    assert not missing, missing


def test_the_weak_form_criterion_matches_the_rules():
    """variants/metrics and phenomena must agree on what a reduced vowel is."""
    from phonotrainer.variants import is_reduced

    # ᵻ (TIMIT ix) for a high front vowel is the same vowel, not a weak form
    assert is_reduced(["h", "ᵻ", "m"], ["h", "ɪ", "m"]) is False
    assert is_reduced(["h", "ə", "m"], ["h", "ɪ", "m"]) is True
    # the article: [ɪ] is its weak form
    assert is_reduced(["ɪ"], ["eɪ"]) is True
    # a syllabic nasal is the extreme case, whichever one it is
    assert is_reduced(["k", "ŋ̍"], ["k", "æ", "n"]) is True
