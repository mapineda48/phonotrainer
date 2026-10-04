"""The timit61 engine: TIMIT-61 labels → IPA, citation form → TIMIT, alignment.

No model is loaded: the engine is built around a synthetic vocabulary and fed
hand-made emissions.
"""

import json
from pathlib import Path

import pytest

from phonotrainer import phones_timit as pt
from phonotrainer.align_canonical import align_words
from phonotrainer.ipa_maps import ENGLISH_INVENTORY

# The model's vocabulary: TIMIT-61 + the word delimiter + [UNK]/[PAD], in its order.
TIMIT_LABELS = (
    "aa ae ah ao aw ax ax-h axr ay b bcl ch d dcl dh dx eh el em en eng epi er ey f g "
    "gcl h# hh hv ih ix iy jh k kcl l m n ng nx ow oy p pau pcl q r s sh t tcl th uh "
    "uw ux v w y z zh |"
).split() + ["[UNK]", "[PAD]"]
VOCAB = {label: i for i, label in enumerate(TIMIT_LABELS)}


def fake_engine() -> pt.TimitPhoneEngine:
    import torch

    engine = object.__new__(pt.TimitPhoneEngine)
    engine.torch = torch
    engine._init_vocab(VOCAB)
    return engine


def units(spec: str, step: float = 0.02):
    """'tcl t uw' → consecutive (label, start, end, score) units."""
    return [(label, round(k * step, 3), round((k + 1) * step, 3), 1.0)
            for k, label in enumerate(spec.split())]


def phones_of(result) -> list[str]:
    return [p["phone"] for p in result]


# --- label mapping ------------------------------------------------------------

def test_every_label_lands_in_the_inventory():
    """Everything the engine can write must be drawable and diffable."""
    produced = set(pt.TIMIT_TO_IPA.values()) | set(pt.CLOSURES.values())
    assert produced <= ENGLISH_INVENTORY, produced - ENGLISH_INVENTORY
    mapped = set(pt.TIMIT_TO_IPA) | set(pt.CLOSURES) | set(pt.SILENCES)
    assert set(TIMIT_LABELS) - {"[UNK]", "[PAD]"} <= mapped


def test_the_canonical_only_uses_labels_of_the_model():
    used = {label for labels in pt.ARPABET_TO_TIMIT.values() for label in labels}
    used |= set(pt.UNSTRESSED_TIMIT.values())
    assert used <= set(VOCAB)


def _cached_vocab():
    root = Path("~/.cache/huggingface/hub/").expanduser()
    found = sorted(root.glob("models--excalibur12--wav2vec2-large-lv60_phoneme-timit_"
                             "english_timit-4k/snapshots/*/vocab.json"))
    return found[0] if found else None


@pytest.mark.skipif(_cached_vocab() is None, reason="TIMIT model not cached")
def test_the_synthetic_vocabulary_is_the_real_one():
    assert json.loads(_cached_vocab().read_text()) == VOCAB


def test_arpabet_to_timit_spells_the_citation_form():
    # every stop carries its closure; unstressed AH/IH/ER are TIMIT's reduced vowels
    assert pt.arpabet_to_timit(["T", "UW1"]) == ["tcl", "t", "uw"]
    assert pt.arpabet_to_timit(["B", "AH1", "T", "AH0", "N"]) == \
        ["bcl", "b", "ah", "tcl", "t", "ax", "n"]
    assert pt.arpabet_to_timit(["B", "ER1", "D"]) == ["bcl", "b", "er", "dcl", "d"]
    assert pt.arpabet_to_timit(["B", "EH1", "T", "ER0"]) == ["bcl", "b", "eh", "tcl", "t", "axr"]
    assert pt.arpabet_to_timit(["R", "OW1", "Z", "IH0", "Z"]) == ["r", "ow", "z", "ix", "z"]
    assert pt.arpabet_to_timit(["CH", "IH1", "JH"]) == ["tcl", "ch", "ih", "dcl", "jh"]


# --- closures -------------------------------------------------------------------

def test_closure_plus_burst_is_one_stop():
    out = pt.merge_units(units("dh ae tcl t"))
    assert phones_of(out) == ["ð", "æ", "t"]
    assert out[-1]["start"] == 0.04 and out[-1]["end"] == 0.08  # spans both
    assert out[-1]["label"] == "tcl t"


def test_closure_without_burst_is_unreleased():
    assert phones_of(pt.merge_units(units("dh ae tcl"))) == ["ð", "æ", "t̚"]
    # act: the /k/ closes and never releases before the /t/
    assert phones_of(pt.merge_units(units("ae kcl tcl t"))) == ["æ", "k̚", "t"]
    # its: the closure runs into the /s/
    assert phones_of(pt.merge_units(units("ih tcl s"))) == ["ɪ", "t̚", "s"]


def test_a_closure_before_a_vowel_released_anyway():
    """A stop cannot run into a vowel or an approximant without releasing: there the
    model missed the burst, and the stop is an ordinary one (got [ɡɑt], not [ɡ̚ɑt])."""
    assert phones_of(pt.merge_units(units("gcl aa tcl t"))) == ["ɡ", "ɑ", "t"]
    assert phones_of(pt.merge_units(units("bcl l ae kcl"))) == ["b", "l", "æ", "k̚"]


def test_affricates_and_bare_bursts():
    assert phones_of(pt.merge_units(units("dcl jh ah dcl jh"))) == ["dʒ", "ʌ", "dʒ"]
    assert phones_of(pt.merge_units(units("t uw"))) == ["t", "u"]  # no closure seen


def test_narrow_labels_and_silences():
    out = pt.merge_units(units("h# w ih nx axr pau bcl b ah q en epi"))
    assert phones_of(out) == ["w", "ɪ", "ɾ̃", "ɚ", "b", "ʌ", "ʔ", "n̩"]


# --- the engine ----------------------------------------------------------------

def _emissions(frames: list[str], engine) -> "torch.Tensor":
    """Log-probs whose argmax spells `frames` ('-' = blank)."""
    import torch

    lp = torch.full((len(frames), len(VOCAB)), -20.0)
    for i, label in enumerate(frames):
        lp[i, engine.blank_id if label == "-" else VOCAB[label]] = 0.0
    return torch.log_softmax(lp, dim=-1)


def test_greedy_phones_collapse_and_merge():
    import numpy as np

    engine = fake_engine()
    frames = "h# h# dh - ae ae tcl tcl - t - pau".split()
    lp = _emissions(frames, engine)
    audio = np.zeros(len(frames) * 320, dtype=np.float32)  # 20 ms per frame
    out = engine.greedy_phones(audio, t_offset=1.0, log_probs=lp)
    assert phones_of(out) == ["ð", "æ", "t"]
    assert out[0]["start"] == 1.04
    assert out[2]["start"] == 1.12 and out[2]["end"] == 1.2


def test_silence_is_folded_into_the_blank_for_alignment():
    import torch

    engine = fake_engine()
    lp = _emissions("h# h# dh ae".split(), engine)
    folded = engine.for_alignment(lp)
    # on a silence frame the blank now carries the silence's probability
    assert folded[0, engine.blank_id] > lp[0, engine.blank_id] + 10
    assert torch.equal(folded[:, VOCAB["dh"]], lp[:, VOCAB["dh"]])


def test_forced_alignment_of_the_citation_form():
    """'that' forced over [h# h# dh ae tcl t]: the /ð/ starts where it is said, not
    on the leading silence, and the /t/ spans its closure and its burst."""
    import numpy as np

    engine = fake_engine()
    frames = "h# h# dh ae ae tcl tcl t".split()
    lp = _emissions(frames, engine)
    audio = np.zeros(len(frames) * 320, dtype=np.float32)
    res = align_words(engine, audio, [{"word": "that", "start": 0.0, "end": 0.16}],
                      log_probs=lp)[0]
    assert not res["fallback"]
    assert res["canonical_units"] == "dh ae tcl t"
    assert phones_of(res["phones"]) == ["ð", "æ", "t"]
    assert res["phones"][0]["start"] == 0.04
    assert res["phones"][2]["start"] == 0.1 and res["phones"][2]["end"] == 0.16


def test_alignment_falls_back_to_uniform_slices():
    import numpy as np

    engine = fake_engine()
    lp = _emissions(["dh"], engine)  # one frame for four units: infeasible
    res = align_words(engine, np.zeros(320, dtype=np.float32),
                      [{"word": "that", "start": 1.0, "end": 1.4}], log_probs=lp)[0]
    assert res["fallback"]
    assert phones_of(res["phones"]) == ["ð", "æ", "t"]
    assert res["phones"][0]["start"] == 1.0 and res["phones"][-1]["end"] == 1.4


def test_realized_phones_are_kept_as_far_as_the_canonical_reaches():
    """Whisper closed "scene!" at 5.36 s; its /n/ peaks at 5.47 s. The keep-window is
    the union of Whisper's segment and the forced canonical (fallbacks excluded)."""
    from phonotrainer.pipeline import REAL_TRIM, _keep_window

    seg = {"start": 3.88, "end": 5.36}
    canon = [{"fallback": False, "phones": [{"start": 3.87, "end": 3.9}]},
             {"fallback": False, "phones": [{"start": 5.2, "end": 5.49}]},
             {"fallback": True, "phones": [{"start": 5.5, "end": 6.5}]}]
    lo, hi = _keep_window(seg, canon)
    assert lo == pytest.approx(3.87 - REAL_TRIM)
    assert hi == pytest.approx(5.49 + REAL_TRIM)
    # never narrower than Whisper's own segment
    lo, hi = _keep_window(seg, [])
    assert (lo, hi) == (pytest.approx(3.88 - REAL_TRIM), pytest.approx(5.36 + REAL_TRIM))


def test_a_short_silence_between_closure_and_burst_does_not_split_the_stop():
    """tcl pau t: one stop held a little longer, not a phantom [t̚] plus a [t]."""
    out = pt.merge_units(units("ae tcl pau t ih"))
    assert phones_of(out) == ["æ", "t", "ɪ"]
    assert out[1]["label"] == "tcl t" and out[1]["end"] == 0.08
    assert phones_of(pt.merge_units(units("ae tcl | t ih"))) == ["æ", "t", "ɪ"]


def test_a_long_pause_after_a_closure_keeps_it_unreleased():
    # that. [ðæt̚] … (a pause) … to: the burst belongs to the next word
    spans = [("ae", 0.0, 0.02, 1.0), ("tcl", 0.02, 0.04, 1.0), ("pau", 0.04, 0.34, 1.0),
             ("t", 0.34, 0.36, 1.0), ("uw", 0.36, 0.38, 1.0)]
    assert phones_of(pt.merge_units(spans)) == ["æ", "t̚", "t", "u"]


def test_a_phone_both_neighboring_segments_decoded_is_kept_once():
    """The padded windows of two segments overlap, and each is decoded on its own:
    a sound in the overlap comes out of both decodes, and kept by both it was an
    insertion in each. The pair is kept once, on its side of the cut where the two
    forced canonicals meet (not Whisper's boundary, which is off either way); a
    phone only one decode heard stays where it is."""
    from phonotrainer.pipeline import _dedupe_overlap, _segment_boundary

    def aligned(*spans):
        return [{"fallback": False, "phones": [{"start": a, "end": b} for a, b in spans]}]

    def ph(label, mid):
        return {"phone": label, "start": round(mid - 0.01, 3), "end": round(mid + 0.01, 3)}

    seg, nxt = {"start": 3.88, "end": 5.36}, {"start": 5.45, "end": 7.0}
    cut = _segment_boundary(seg, aligned((3.90, 3.95), (5.40, 5.52)),
                            nxt, aligned((5.40, 5.46), (6.0, 6.9)))
    assert cut == pytest.approx(5.46)
    assert _segment_boundary(seg, [], nxt, []) == pytest.approx((5.36 + 5.45) / 2)

    prev = [ph("æ", 5.30), ph("n", 5.42), ph("ʔ", 5.50)]     # ʔ: only this decode
    nxt_phones = [ph("n", 5.43), ph("b", 5.55), ph("ɪ", 5.62)]
    keep_prev, keep_next = _dedupe_overlap(prev, nxt_phones, cut)
    assert [p["phone"] for p in keep_prev] == ["æ", "n", "ʔ"]   # the n pair sits before the cut
    assert [p["phone"] for p in keep_next] == ["b", "ɪ"]

    # the same pair past the cut goes to the next segment instead
    keep_prev, keep_next = _dedupe_overlap([ph("n", 5.50)], [ph("n", 5.51)], cut)
    assert keep_prev == [] and [p["phone"] for p in keep_next] == ["n"]
