"""report.html: what the backend produces is on the page, and analyses made
before a field existed still render."""

import copy

from conftest import mk_analysis

from phonotrainer import metrics
from phonotrainer.report import render_html


def _current() -> dict:
    """mk_analysis with every field the current pipeline writes."""
    a = mk_analysis()
    a["meta"].update({
        "phone_engine": "timit61",
        "models": {"asr": "faster-whisper small (int8)",
                   "phones": "excalibur12/wav2vec2-large-lv60_phoneme-timit_english_timit-4k",
                   "alignment": "torchaudio forced_align of the CMUdict citation form"},
        "attraction": False,
        "dialogue_separation": {"applied": True, "model": "htdemucs",
                                "audio": "audio_dialogue.wav", "seconds": 4.2},
    })
    seg0, seg1 = a["segments"]
    seg0["intonation_units"][0].update({
        "type": "statement", "final_contour": "rising", "expected_contour": "falling",
        "matches_expected": False, "uptalk": True,
    })
    seg0["rhythm"] = {"npvi": 41.3, "varco": 30.0, "n_intervals": 5, "n_pairs": 4,
                      "mean_ms": 150.0, "sd_ms": 45.0, "approximate": True,
                      "method": "inter-nucleus intervals (CTC peaks)"}
    seg0["f0_stats"].update({"range_st": 6.4, "final_slope_st": 5.2})
    does = seg0["words"][0]
    does.update({"boundary_link_type": "r", "variant_labels": ["vowel_reduction"],
                 "form": {"ipa": "dəz", "strong_ipa": "dʌz", "weak": True,
                          "weak_margin": 2.7, "scores": {"dəz": 0.0, "dʌz": -2.7}}})
    seg1["words"][0]["lexical_form"] = "finna"
    a["summary"]["metrics"] = metrics.compute(a)
    return a


def _legacy() -> dict:
    """An analysis.json from before the timit61 engine, prosody units, metrics,
    link types and form scoring existed."""
    a = copy.deepcopy(mk_analysis())
    for seg in a["segments"]:
        for key in ("prominence", "word_classes", "intonation_units", "rhythm"):
            seg.pop(key, None)
    a["meta"].pop("phone_engine", None)
    a["summary"].pop("metrics", None)
    return a


def test_current_analysis_shows_engine_separation_and_provenance():
    page = render_html(_current())
    assert "Phone engine timit61" in page
    assert "LDC93S1" in page and "non-commercial" in page
    assert "Dialogue separated from music and effects" in page


def test_metrics_sit_next_to_their_references():
    page = render_html(_current())
    assert "How much of this clip is reduced" in page
    assert "Johnson 2004" in page and "Patterson &amp; Connine 2001" in page
    assert metrics.METRIC_LABELS["deviate"] in page
    assert 'class="meter"' in page


def test_practice_axis_is_in_the_legend_and_the_detail_table():
    page = render_html(_current())
    assert "Produce it or only recognize it?" in page
    assert "recognize only" in page
    # the reduced form carries its own advice: finna is marked, recognize only
    assert "“finna”" in page and "recognize only, marked" in page


def test_link_type_form_scoring_and_prosody_are_shown():
    page = render_html(_current())
    assert 'title="linking: linking r"' in page
    assert "vowel reduction*" in page
    assert "from weak/strong form scoring" in page
    assert "form scoring</span> weak form" in page
    assert "uptalk" in page and "is expected to end ↘ falling" in page
    assert "rhythm nPVI 41.3 (approximate)" in page
    assert "<th>prominence</th>" in page


def test_legacy_analysis_still_renders_with_metrics_computed_on_the_spot():
    page = render_html(_legacy())
    assert "Phone engine espeak" in page
    assert "How much of this clip is reduced" in page   # computed, not stored
    assert 'class="units"' not in page
    assert "<th>prominence</th>" not in page
    assert "* = label from weak/strong form scoring" not in page


def test_failed_separation_says_the_original_mix_was_analyzed():
    a = _current()
    a["meta"]["dialogue_separation"] = {"applied": False, "model": "htdemucs",
                                        "error": "no module named demucs"}
    page = render_html(a)
    assert "Dialogue separation failed (no module named demucs)" in page


def test_text_is_escaped():
    a = _current()
    a["segments"][0]["words"][1]["word"] = "<script>x</script>"
    a["segments"][0]["intonation_units"][0]["text"] = "<b>unit</b>"
    page = render_html(a)
    assert "<script>x</script>" not in page and "&lt;script&gt;x" in page
    assert "<b>unit</b>" not in page


def test_a_segment_too_short_for_a_range_still_renders():
    # prosody reports a mean but no range below 0.1 s of voicing
    a = _current()
    a["segments"][0]["f0_stats"].update({"range": None, "range_st": None})
    page = render_html(a)
    assert "range —" in page
