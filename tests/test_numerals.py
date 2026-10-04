"""Numerals and symbols get the canonical of their spoken form (numerals.py).

"9", "9.30", "$5" have no dictionary entry: before this, their canonical was
empty, every phone heard there was an insertion, and they headed the corpus's
most-divergent list. No model is loaded anywhere here.
"""

from pathlib import Path

import pytest
from conftest import mk_analysis_word

from phonotrainer import metrics, numerals, phenomena
from phonotrainer.canonical import canonical_source, dict_pronunciation
from phonotrainer.db import Corpus


# --- the expander -------------------------------------------------------------

@pytest.mark.parametrize("tokens, spoken", [
    (["7"], ["seven"]),
    (["12"], ["twelve"]),
    (["115"], ["one hundred fifteen"]),
    (["999,999"], ["nine hundred ninety-nine thousand nine hundred ninety-nine"]),
    (["1999"], ["nineteen ninety-nine"]),
    (["1905"], ["nineteen oh five"]),
    (["1900"], ["nineteen hundred"]),
    (["2005"], ["two thousand five"]),
    (["2024"], ["twenty twenty-four"]),
    (["9:30"], ["nine thirty"]),
    (["9:05"], ["nine oh five"]),
    (["9:00"], ["nine o'clock"]),
    (["3.5"], ["three point five"]),
    (["1st", "2nd", "3rd", "21st", "30th"],
     ["first", "second", "third", "twenty-first", "thirtieth"]),
    (["$5"], ["five dollars"]),
    (["$1"], ["one dollar"]),
    (["$5.50"], ["five dollars and fifty cents"]),
    (["50%"], ["fifty percent"]),
    (["%"], ["percent"]),
    (["80s", "1990s"], ["eighties", "nineteen nineties"]),
    (["911"], ["nine one one"]),
    (["007"], ["zero zero seven"]),
    (["-5"], ["minus five"]),
    (["COVID-19"], ["covid nineteen"]),
    (["hello", "don't"], [None, None]),
])
def test_standalone_tokens(tokens, spoken):
    assert numerals.expand_tokens(tokens) == spoken


@pytest.mark.parametrize("tokens, spoken", [
    # Whisper splits a numeral across several "words": each fragment gets what
    # is said over its own stretch of audio
    (["in", "bed", "at", "9", ".30."], [None, None, None, "nine", "thirty"]),
    (["at", "9", ":05"], [None, "nine", "oh five"]),
    (["Over", "5", ",000."], [None, "five", "thousand"]),
    (["1", ",250", ",000"], ["one", "million two hundred fifty", "thousand"]),
    (["5", ",000", ",000"], ["five", "million", ""]),      # a fragment with no words
    (["$5", ",000"], ["five", "thousand dollars"]),
    (["nanny", "9", "-1", "-1", "we've"], [None, "nine", "one", "one", None]),
    (["COVID", "-19"], [None, "nineteen"]),                   # a name, not "minus"
    (["over", "7", "million"], [None, "seven", None]),
    (["3", ".5"], ["three", "point five"]),
])
def test_split_numerals(tokens, spoken):
    assert numerals.expand_tokens(tokens) == spoken


def test_ordinals_and_plural_spellings():
    assert numerals.ordinal(12) == "twelfth"
    assert numerals.ordinal(100) == "one hundredth"
    assert numerals.cardinal(1_000_001) == "one million one"


# --- the canonical built from it -----------------------------------------------

def test_the_dictionary_form_of_a_spoken_numeral_concatenates_its_words():
    dic = dict_pronunciation(canonical_source("9.30", "nine thirty"))
    assert dic["arpabet"] == ["N", "AY1", "N", "TH", "ER1", "D", "IY0"]
    assert dic["oov"] is False
    # hyphens split: "ninety-nine" is two dictionary words, not one OOV guess
    assert not dict_pronunciation(canonical_source("99", "ninety-nine"))["oov"]
    # ordinary words are untouched, and a bare digit still has nothing
    assert canonical_source("better") == "better"
    assert dict_pronunciation("9")["arpabet"] == []


def test_the_espeak_tokenizer_reads_the_spoken_form():
    from phonotrainer.align_canonical import canonical_phone_ids

    seen = []

    class Tok:
        all_special_ids, unk_token_id = [], -1

        def __call__(self, text):
            seen.append(text)
            return type("Enc", (), {"input_ids": [1, 2]})()

    canonical_phone_ids(Tok(), canonical_source("$5", "five dollars"))
    canonical_phone_ids(Tok(), "Hello,")
    assert seen == ["five dollars", "hello"]


def test_alignment_uses_the_spoken_form_as_one_word():
    import numpy as np
    from test_timit import _emissions, fake_engine

    from phonotrainer.align_canonical import align_words

    engine = fake_engine()
    frames = "h# n n ay ay n n h#".split()
    res = align_words(engine, np.zeros(len(frames) * 320, dtype=np.float32),
                      [{"word": "9", "start": 0.0, "end": 0.16, "canonical_text": "nine"}],
                      log_probs=_emissions(frames, engine))[0]
    assert res["canonical_units"] == "n ay n"
    assert [p["phone"] for p in res["phones"]] == ["n", "aɪ", "n"]


# --- words left with no canonical ------------------------------------------------

def _entry(word, canonical, real, t0=0.0):
    def phones(spec, start):
        return [{"phone": p, "start": round(start + 0.06 * k, 3),
                 "end": round(start + 0.06 * k + 0.05, 3)} for k, p in enumerate(spec.split())]
    real_phones = phones(real, t0)
    return {"word": word, "start": t0, "end": t0 + 0.3, "canonical": phones(canonical, t0),
            "real": real_phones, "alignment_fallback": False, "dict_arpabet": [], "oov": True}


def test_a_word_without_a_canonical_gets_no_labels():
    words = phenomena.detect([
        _entry("#", "", "n ʌ m b ɚ"),            # heard, but nothing to compare to
        _entry("about", "ɐ b aʊ t", "ɐ b aʊ", t0=0.4),
    ])
    assert words[0]["phenomena"] == []
    assert words[0]["boundary_link_next"] is False
    assert "t_unreleased" in words[1]["phenomena"] or "t_deletion" in words[1]["phenomena"]


def test_metrics_leave_out_words_without_a_canonical():
    # an analysis made before numerals were spoken out: "9" has an empty canonical,
    # insertions everywhere, and a label the old rules gave it
    old = {"meta": {}, "segments": [{"words": [
        mk_analysis_word("9", 0.0, "", "n aɪ n", phenomena=["word_elision"]),
        mk_analysis_word("dog", 0.4, "d ɔ ɡ", "d ɔ ɡ"),
    ]}]}
    m = metrics.compute(old)
    assert m["words"]["total"] == 2 and m["words"]["no_canonical"] == 1
    assert m["words"]["analyzed"] == 1
    assert m["deviate"]["of"] == 1
    assert "word_elision" not in m["labels"]
    # the flag itself is honored as well
    flagged = mk_analysis_word("%", 0.8, "p ɚ s ɛ n t", "p ɚ s ɛ n t", no_canonical=True)
    assert not metrics.has_canonical(flagged)
    pooled = metrics.aggregate([m, m])
    assert pooled["words"]["no_canonical"] == 2


def test_the_corpus_ranks_and_counts_only_words_with_a_canonical():
    analysis = {"meta": {"source": "clip.webm"}, "segments": [{"words": [
        mk_analysis_word("9", 0.0, "", "n aɪ n", phenomena=["word_elision"], diff_cost=2.1),
        mk_analysis_word("that", 0.4, "ð æ t", "ð æ", phenomena=["t_unreleased"],
                         diff_cost=0.23),
    ]}]}
    db = Corpus(":memory:")
    try:
        db.index_analysis("a1", analysis)
        assert [o["word"] for o in db.occurrences()] == ["that"]
        assert db.count_occurrences() == 1
        assert {p["phenomenon"] for p in db.stats()["phenomena"]} == {"t_unreleased"}
        assert [t["word"] for t in db.stats()["top_words"]] == ["that"]
        # searching for it on purpose still finds it
        assert [o["word"] for o in db.occurrences(word="9")] == ["9"]
    finally:
        db.close()


# --- the whole pipeline, with fakes ---------------------------------------------

def test_the_pipeline_speaks_numerals_out(tmp_path, monkeypatch):
    """'at 9.30 #' through pipeline.analyze with a fake TIMIT engine: "9" and
    ".30" are aligned as "nine" and "thirty"; "#" is left with no canonical."""
    import numpy as np
    import soundfile as sf
    from test_timit import _emissions, fake_engine

    from phonotrainer import pipeline

    sr, dur = 16000, 1.2

    def fake_extract(media, out):
        t = np.arange(int(sr * dur)) / sr
        sf.write(str(out), (0.1 * np.sin(2 * np.pi * 150 * t)).astype(np.float32), sr)
        return Path(out)

    transcript = {
        "language": "en", "language_probability": 1.0, "duration": dur,
        "segments": [{"start": 0.2, "end": 1.0, "text": "at 9.30 #", "words": [
            {"word": "at", "start": 0.2, "end": 0.35, "probability": 1.0},
            {"word": "9", "start": 0.35, "end": 0.55, "probability": 1.0},
            {"word": ".30", "start": 0.55, "end": 0.85, "probability": 1.0},
            {"word": "#", "start": 0.85, "end": 1.0, "probability": 1.0},
        ]}],
    }
    engine = fake_engine()
    script = "ae tcl t n ay n th er dcl d iy".split()

    def log_probs(audio):
        frames = ["h#"] * max(len(audio) // 320, len(script) * 2 + 8)
        for k, label in enumerate(script):
            frames[6 + 2 * k] = frames[7 + 2 * k] = label
        return _emissions(frames, engine)

    engine.log_probs = log_probs
    monkeypatch.setattr(pipeline, "extract_audio", fake_extract)
    monkeypatch.setattr(pipeline, "transcribe", lambda *a, **k: transcript)
    monkeypatch.setattr(pipeline, "build_engine", lambda *a, **k: engine)

    analysis = pipeline.analyze(tmp_path / "clip.wav", tmp_path / "out",
                                separate_dialogue=False)
    words = {w["word"]: w for w in analysis["segments"][0]["words"]}

    assert words["9"]["canonical_text"] == "nine"
    assert words["9"]["canonical_ipa"] == "naɪn" and words["9"]["no_canonical"] is False
    assert words[".30"]["canonical_text"] == "thirty"
    assert words[".30"]["dict_ipa"].replace("ˈ", "") == "θɝdi"
    assert words["#"]["no_canonical"] is True and words["#"]["phenomena"] == []
    assert words["at"]["canonical_text"] is None
    assert analysis["meta"]["rules_version"] == phenomena.RULES_VERSION
    assert analysis["summary"]["metrics"]["words"]["no_canonical"] == 1
    # transcript.json keeps what Whisper wrote
    assert "canonical_text" not in (tmp_path / "out" / "transcript.json").read_text()
