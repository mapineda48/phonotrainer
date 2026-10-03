"""Self-contained report.html: transcript colored by phenomenon family,
canonical-vs-realized tooltips, an SVG F0 sparkline per segment, detail table.

Colors: 4 categorical slots validated (all-pairs, light/dark) from the
reference system; lexical contraction is encoded without color (dotted
underline + a chip with the reduced form). Identity is never color alone: the
legend, the tooltip and the table all name each phenomenon. Everything else
(the reduction meters, prominence bars) is drawn in the neutral ink tokens, so
the four family slots stay the only hues on the page.

Analyses made before a field existed still render: every newer field (metrics,
intonation units, prominence, link types, form scoring, engine, separation) is
optional, and the metrics are computed on the spot when the summary lacks them.
"""

from __future__ import annotations

import html
import json
import math

from . import metrics as metrics_mod
from .phones_real import engine_of

FAMILY_OF = {
    "vowel_reduction": "reduction",
    "monophthongization": "reduction",
    "elision_syllable": "reduction",
    "function_elision": "reduction",
    "flapping": "td",          # t/d processes
    "t_deletion": "td",
    "t_unreleased": "td",
    "glottalization": "td",
    "nt_reduction": "td",
    "th_stopping": "assimilation",
    "palatalization": "assimilation",
    "place_assimilation": "assimilation",
    "linking": "boundary",
    "h_dropping": "boundary",
    # word_elision gets no color: it is shown dimmed (low confidence)
}

FAMILY_LABEL = {
    "reduction": "Reduction",
    "td": "t/d processes",
    "assimilation": "Assimilation",
    "boundary": "Word boundary",
}

FAMILY_MEMBERS = {
    "reduction": ["vowel_reduction", "monophthongization", "elision_syllable",
                  "function_elision"],
    "td": ["flapping", "t_deletion", "t_unreleased", "glottalization", "nt_reduction"],
    "assimilation": ["th_stopping", "palatalization", "place_assimilation"],
    "boundary": ["linking", "h_dropping"],
}

PHENOMENON_LABEL = {
    "vowel_reduction": "vowel reduction",
    "monophthongization": "monophthongization",
    "elision_syllable": "elided syllable",
    "word_elision": "elided word",
    "flapping": "flapping",
    "t_deletion": "t/d deletion",
    "t_unreleased": "unreleased t/d",
    "glottalization": "glottalization",
    "th_stopping": "th-stopping",
    "palatalization": "palatalization",
    "linking": "linking",
    "h_dropping": "h-dropping",
    "contraction_lex": "lexical contraction",
    "place_assimilation": "place assimilation",
    "nt_reduction": "nt → n",
    "function_elision": "function-word elision",
}

# What each phenomenon is, in one sentence and with the canonical example from
# the rules table (task.md, phase 5). The interface shows them next to every
# label: without this, "glottalization" means nothing to someone who is learning.
PHENOMENON_DESCRIPTION = {
    "vowel_reduction": "A full vowel reduces to schwa in an unstressed syllable. does → dəz",
    "monophthongization": "A diphthong is realized as a plain vowel. my → ma",
    "elision_syllable": "A whole syllable is lost. probably → pɹɑbli, camera → kæmɹə",
    "word_elision": "The word leaves no acoustic trace: fast speech swallows it.",
    "flapping": "/t/ or /d/ between vowels sounds like a soft r. water → wɔɾɚ",
    "t_deletion": "A final /t/ or /d/ drops out of a consonant cluster. next day → nɛks deɪ",
    "t_unreleased": "A final /t/ or /d/ closes but is never released: an unreleased [t̚]. "
                    "(The espeak engine cannot hear the closure and infers it from a /t/ "
                    "missing after a vowel.) that → ðæt̚",
    "glottalization": "/t/ closes into a glottal stop. button → bʌʔn̩",
    "th_stopping": "θ and ð are pronounced as the stops t/d. that → dat",
    "palatalization": "t+j and d+j merge into tʃ/dʒ at the end of a word. got you → gotcha",
    "linking": "The word runs into the next vowel: a final consonant (does it → dʌ‿zɪt), "
               "an r (far away → fɑɹ‿əweɪ) or a w/j glide (go on → ɡoʊ‿wɑn).",
    "h_dropping": "The unstressed h disappears. tell him → tell im",
    "contraction_lex": "A lexicalized reduced form. want to → wanna",
    "place_assimilation": "A final n/t/d takes the place of the next consonant. "
                          "ten bucks → tɛm bʌks, in case → ɪŋ keɪs",
    "nt_reduction": "/nt/ before a vowel loses its /t/: a plain n or a nasal flap. "
                    "twenty → twɛni, winter → wɪɾ̃ɚ",
    "function_elision": "A function word loses a consonant as well as its vowel. "
                        "of → ə, them → əm",
}

# Learn it to hear it, or to say it too? The report (§5, §7, Recommendations)
# separates what native speakers DO from what a learner should PRODUCE:
#   practice: "produce"    = safe and worth imitating;
#             "understand" = learn to recognize it, don't imitate it;
#   register: "universal"  = every speaker, nearly every register;
#             "casual"     = common, but in relaxed or fast speech;
#             "marked"     = regional or social (AAVE, Southern, British…).
PHENOMENON_PRACTICE = {
    "vowel_reduction": {
        "practice": "produce", "register": "universal",
        "why": "Unstressed vowels turning to schwa is the engine of English rhythm, and "
               "the first thing the report says to produce (§4, stage 2)."},
    "monophthongization": {
        "practice": "understand", "register": "marked",
        "why": "Diphthongs flattened into one vowel (Southern /aɪ/ → [aː]) are regional "
               "(§5): recognize them, don't imitate them."},
    "elision_syllable": {
        "practice": "understand", "register": "casual",
        "why": "Schwa deletion (famly, prob'ly, camra) is very common but casual: "
               "understanding it comes first (§3E, §5)."},
    "word_elision": {
        "practice": "understand", "register": "casual",
        "why": "A word swallowed by fast speech: something to hear, not to aim for."},
    "flapping": {
        "practice": "produce", "register": "universal",
        "why": "Safe to produce and one of the most rewarding things to learn (§3A)."},
    "t_deletion": {
        "practice": "understand", "register": "casual",
        "why": "Dropping the t/d of a cluster (next day, old man) belongs to casual "
               "speech; all dialects do it before a consonant (§3E, §5)."},
    "t_unreleased": {
        "practice": "produce", "register": "universal",
        "why": "A final /t/ held without a burst (what, that) is the everyday American "
               "form; releasing it fully sounds over-careful (§3B)."},
    "glottalization": {
        "practice": "produce", "register": "universal",
        "why": "Safe before a syllabic n (button, important — stage 3); before a vowel "
               "it is optional and led by young Western speakers (§3B)."},
    "th_stopping": {
        "practice": "understand", "register": "marked",
        "why": "th → d/t is an AAVE and regional feature (§5): recognize it; a learner "
               "should keep working on θ/ð instead (§7)."},
    "palatalization": {
        "practice": "produce", "register": "universal",
        "why": "didja, gotcha, doncha are universal and safe to produce (§3D)."},
    "linking": {
        "practice": "produce", "register": "universal",
        "why": "Consonant–vowel linking, linking r and the w/j glides are fundamental "
               "to fluency and safe to produce (§3G)."},
    "h_dropping": {
        "practice": "produce", "register": "universal",
        "why": "Safe in unstressed pronouns and auxiliaries — but never at the start "
               "of a sentence (§3C)."},
    "contraction_lex": {
        "practice": "produce", "register": "universal",
        "why": "gonna, wanna, gotta, gimme, lemme, kinda are universal and safe; some "
               "forms are marked — see each form (§1)."},
    "place_assimilation": {
        "practice": "understand", "register": "casual",
        "why": "tem bucks, ing case happen only in fast, natural speech; forced, they "
               "sound odd (§3F, §7)."},
    "nt_reduction": {
        "practice": "understand", "register": "marked",
        "why": "winter → winner is very American and widespread but variable; the "
               "report files it under understand only (§5)."},
    "function_elision": {
        "practice": "produce", "register": "universal",
        "why": "of → ə and them → əm are the weak forms the report says to produce "
               "(§2, stage 2): lotsa, kinda, tell 'em."},
}

# The same axis for each lexical reduced form (report §1 and its notes): "almost
# all are safe to produce in an informal register, except the marked ones".
_PRODUCE_UNIVERSAL = {"practice": "produce", "register": "universal",
                      "why": "Universal informal form, safe to produce (§1)."}
_PRODUCE_CASUAL = {"practice": "produce", "register": "casual",
                   "why": "Common informal form: fine in relaxed speech (§1)."}
_UNDERSTAND_MARKED = {"practice": "understand", "register": "marked",
                      "why": "Regionally or socially marked: understand it, don't "
                             "produce it (§1, §5)."}
LEXICAL_PRACTICE = {
    **dict.fromkeys(("gonna", "wanna", "gotta", "hafta", "lemme", "gimme", "dunno",
                     "kinda", "outta", "gotcha", "gotchu", "coulda", "shoulda",
                     "woulda", "didja", "didya", "dontcha", "doncha", "wouldja",
                     "couldja", "'em", "cmon", "c'mon"), _PRODUCE_UNIVERSAL),
    **dict.fromkeys(("hasta", "oughta", "useta", "usta", "supposta", "sposta",
                     "s'posta", "sorta", "lotta", "lotsa", "betcha", "whatcha",
                     "watcha", "whatchu", "whaddya", "whaddaya", "musta", "mighta",
                     "ya", "cuz", "'cuz", "'cause"), _PRODUCE_CASUAL),
    "tryna": {**_UNDERSTAND_MARKED,
              "why": "Younger and AAVE speech (§1): understand it; from a non-native "
                     "speaker it can sound put on."},
    "finna": {**_UNDERSTAND_MARKED,
              "why": "Southern and AAVE (fixing to, §1): understand it only."},
    "imma": {**_UNDERSTAND_MARKED,
             "why": "I'm gonna reduced further, associated with AAVE: understand it only."},
    "cuppa": {**_UNDERSTAND_MARKED,
              "why": "Mostly British (§1): recognize it in American speech, don't adopt it."},
}

PRACTICE_LABEL = {"produce": "produce", "understand": "recognize only"}

LINK_TYPE_LABEL = {
    "consonant": "consonant",
    "r": "linking r",
    "glide_w": "w glide",
    "glide_j": "j glide",
}

UNIT_TYPE_LABEL = {
    "statement": "statement",
    "yes_no_question": "yes/no question",
    "wh_question": "wh-question",
    "exclamation": "exclamation",
    "incomplete": "unfinished",
}

# What the numbers of each engine can and cannot be trusted for (see phones_real
# and phones_timit): shown next to the engine and under the reduction metrics.
ENGINE_NOTE = {
    "timit61": "narrow TIMIT-61 recognizer; the canonical is the CMUdict citation "
               "form, aligned on the same model. Trained on TIMIT (LDC93S1), which "
               "the LDC licenses for non-commercial research; the weights are "
               "Apache-2.0.",
    "espeak": "recognizer trained on espeak-ng pronunciations: it tends to hear the "
              "dictionary form, so weak forms and glottal stops are under-reported.",
}

# The rows of the reduction card, in reading order: the scale of reduction
# first, then the processes that can only happen in a given context.
METRIC_ROWS = ("deviate", "segment_loss", "syllable_loss", "schwa_share",
               "function_words", "weak_forms", "flapping",
               "glottal_before_syllabic_n", "glottal_prevocalic")

_CSS = """
:root { color-scheme: light dark; }
body {
  margin: 0; background: var(--page); color: var(--ink);
  font: 15px/1.55 system-ui, -apple-system, "Segoe UI", sans-serif;
}
.wrap { max-width: 880px; margin: 0 auto; padding: 28px 20px 80px; }
.viz-root {
  --page: #f9f9f7; --surface: #fcfcfb; --ink: #0b0b0b; --ink-2: #52514e;
  --ink-muted: #898781; --grid: #e1e0d9; --baseline: #c3c2b7;
  --border: rgba(11,11,11,0.10);
  --fam-reduction: #2a78d6; --fam-td: #008300;
  --fam-assimilation: #e87ba4; --fam-boundary: #eda100;
  --f0: #4a3aa7;
  --tint-reduction: rgba(42,120,214,.13); --tint-td: rgba(0,131,0,.12);
  --tint-assimilation: rgba(232,123,164,.16); --tint-boundary: rgba(237,161,0,.15);
  --ref-band: rgba(11,11,11,.20);
}
@media (prefers-color-scheme: dark) {
  .viz-root {
    --page: #0d0d0d; --surface: #1a1a19; --ink: #ffffff; --ink-2: #c3c2b7;
    --ink-muted: #898781; --grid: #2c2c2a; --baseline: #383835;
    --border: rgba(255,255,255,0.10);
    --fam-reduction: #3987e5; --fam-td: #008300;
    --fam-assimilation: #d55181; --fam-boundary: #c98500;
    --f0: #9085e9;
    --tint-reduction: rgba(57,135,229,.22); --tint-td: rgba(0,131,0,.22);
    --tint-assimilation: rgba(213,81,129,.22); --tint-boundary: rgba(201,133,0,.22);
    --ref-band: rgba(255,255,255,.34);
  }
}
h1 { font-size: 22px; margin: 0 0 2px; }
.meta { color: var(--ink-muted); font-size: 13px; margin-bottom: 24px; }
.card {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: 10px; padding: 16px 18px; margin: 14px 0;
}
.legend { display: flex; flex-wrap: wrap; gap: 14px 22px; font-size: 13px; }
.legend .item { display: flex; align-items: center; gap: 7px; }
.dot { width: 10px; height: 10px; border-radius: 3px; flex: 0 0 auto; }
.legend .members { color: var(--ink-muted); }
.legend .marks { color: var(--ink-2); }
.prov { color: var(--ink-2); font-size: 12.5px; margin: -16px 0 22px; }
.prov div { margin: 2px 0; }
.tag {
  display: inline-block; font-size: 11px; color: var(--ink-2); white-space: nowrap;
  border: 1px solid var(--border); border-radius: 8px; padding: 0 6px; line-height: 1.5;
}
.muted { color: var(--ink-muted); }
.note { color: var(--ink-muted); font-size: 12px; margin: 8px 0 0; }
.meter {
  position: relative; display: inline-block; width: 120px; height: 8px;
  border-radius: 4px; background: var(--grid); vertical-align: middle;
}
.meter .band { position: absolute; top: 0; bottom: 0; background: var(--ref-band); }
.meter .val {
  position: absolute; top: -3px; width: 3px; height: 14px; margin-left: -1.5px;
  border-radius: 2px; background: var(--ink);
}
td.num { font-variant-numeric: tabular-nums; white-space: nowrap; }
.units { list-style: none; margin: 4px 0 8px; padding: 0; font-size: 12.5px; color: var(--ink-2); }
.units li { margin: 3px 0; }
.units .utext { color: var(--ink); }
.pbar {
  display: inline-block; height: 6px; border-radius: 0 3px 3px 0;
  background: var(--ink-muted); vertical-align: middle; margin-right: 6px;
}
.sumrow { display: grid; grid-template-columns: 170px 1fr 40px 104px; gap: 10px;
  align-items: center; font-size: 13px; margin: 6px 0; }
.sumbar { height: 8px; border-radius: 0 4px 4px 0; min-width: 2px; }
.sumrow .n { text-align: right; font-variant-numeric: tabular-nums; color: var(--ink-2); }
.seg-head { display: flex; align-items: baseline; gap: 14px; flex-wrap: wrap; }
.seg-time { color: var(--ink-muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.f0meta { color: var(--ink-2); font-size: 12px; }
.text { font-size: 17px; margin: 10px 0 6px; line-height: 2.1; }
.w { position: relative; border-radius: 4px; padding: 1px 2px; }
.w.hl { background: var(--tint); box-shadow: inset 0 -2px 0 var(--fam); }
.w.contr { text-decoration: underline dotted 2px var(--ink-2); text-underline-offset: 4px; }
.w.emph { font-weight: 700; }
.w.lowconf { opacity: .55; }
.chip {
  font-size: 10.5px; color: var(--ink-2); border: 1px solid var(--border);
  border-radius: 8px; padding: 0 5px; margin-left: 3px; vertical-align: super;
}
.tie { color: var(--ink-2); }
.w .tip {
  display: none; position: absolute; z-index: 3; left: 0; top: calc(100% + 6px);
  background: var(--surface); border: 1px solid var(--border); border-radius: 8px;
  padding: 8px 10px; font-size: 12.5px; line-height: 1.5; width: max-content;
  max-width: 300px; box-shadow: 0 4px 14px rgba(0,0,0,.14); font-weight: 400;
}
.w:hover .tip { display: block; }
.tip .ipa { font-size: 14px; }
.tip .lbl { color: var(--ink-muted); }
.ipa { font-family: "Charis SIL", "Doulos SIL", "Gentium Plus", "DejaVu Sans", system-ui, sans-serif; }
details { margin-top: 8px; }
summary { cursor: pointer; color: var(--ink-2); font-size: 13px; }
table { border-collapse: collapse; width: 100%; margin-top: 8px; font-size: 13px; }
th { text-align: left; color: var(--ink-muted); font-weight: 500; }
th, td { padding: 4px 10px 4px 0; border-bottom: 1px solid var(--grid); vertical-align: top; }
td.t { font-variant-numeric: tabular-nums; color: var(--ink-2); white-space: nowrap; }
.spark { display: block; }
.spark .axis { stroke: var(--baseline); stroke-width: 1; }
.spark .line { stroke: var(--f0); stroke-width: 2; fill: none; }
.spark .pt { fill: var(--f0); }
.spark text { fill: var(--ink-muted); font-size: 9px; font-variant-numeric: tabular-nums; }
.spark .cross { stroke: var(--grid); stroke-width: 1; visibility: hidden; }
.sparkwrap { position: relative; }
.sparktip {
  position: absolute; display: none; background: var(--surface);
  border: 1px solid var(--border); border-radius: 6px; padding: 2px 7px;
  font-size: 11px; color: var(--ink-2); pointer-events: none; white-space: nowrap;
}
"""

_JS = """
document.querySelectorAll('.sparkwrap').forEach(function (wrap) {
  var svg = wrap.querySelector('svg'), tip = wrap.querySelector('.sparktip');
  var cross = svg.querySelector('.cross');
  var pts = JSON.parse(svg.dataset.pts);   // [[x,y,t,hz], …]
  if (!pts.length) return;
  svg.addEventListener('mousemove', function (ev) {
    var r = svg.getBoundingClientRect(), x = ev.clientX - r.left;
    var best = pts[0];
    for (var i = 1; i < pts.length; i++)
      if (Math.abs(pts[i][0] - x) < Math.abs(best[0] - x)) best = pts[i];
    cross.setAttribute('x1', best[0]); cross.setAttribute('x2', best[0]);
    cross.style.visibility = 'visible';
    tip.style.display = 'block';
    tip.style.left = Math.min(best[0] + 8, r.width - 90) + 'px';
    tip.style.top = '-4px';
    tip.textContent = best[2].toFixed(2) + ' s · ' + Math.round(best[3]) + ' Hz';
  });
  svg.addEventListener('mouseleave', function () {
    cross.style.visibility = 'hidden'; tip.style.display = 'none';
  });
});
"""


def _esc(s: str) -> str:
    return html.escape(str(s), quote=True)


def _sparkline(track: list[list[float]], t0: float, t1: float,
               width: int = 260, height: int = 44) -> str:
    if len(track) < 2:
        return ""
    step = max(1, math.ceil(len(track) / 80))
    pts = track[::step]
    hz = [p[1] for p in pts]
    lo, hi = min(hz), max(hz)
    span = max(hi - lo, 1.0)
    pad, lab = 4, 30
    def sx(t):
        return lab + (t - t0) / max(t1 - t0, 1e-6) * (width - lab - pad)
    def sy(v):
        return pad + (1 - (v - lo) / span) * (height - 2 * pad - 8)
    xy = [(round(sx(t), 1), round(sy(v), 1), t, v) for t, v in pts]
    poly = " ".join(f"{x},{y}" for x, y, _, _ in xy)
    data = json.dumps([[x, y, t, v] for x, y, t, v in xy])
    lx, ly = xy[-1][0], xy[-1][1]
    return f"""<div class="sparkwrap">
<svg class="spark" width="{width}" height="{height}" data-pts='{_esc(data)}' role="img"
     aria-label="F0 contour of the segment, {lo:.0f} to {hi:.0f} Hz">
  <line class="axis" x1="{lab}" y1="{height - 6}" x2="{width - pad}" y2="{height - 6}"/>
  <line class="cross" x1="0" x2="0" y1="{pad}" y2="{height - 6}"/>
  <text x="{lab - 4}" y="{pad + 8}" text-anchor="end">{hi:.0f}</text>
  <text x="{lab - 4}" y="{height - 8}" text-anchor="end">{lo:.0f}</text>
  <polyline class="line" points="{poly}"/>
  <circle class="pt" cx="{lx}" cy="{ly}" r="2.5"/>
</svg>
<div class="sparktip"></div>
</div>"""


_ARROW = {"rising": "↗ rising", "falling": "↘ falling", "flat": "→ flat"}


def _signed(x: float, digits: int = 1) -> str:
    """+8.7 / −8.0 with a real minus sign."""
    return f"{x:+.{digits}f}".replace("-", "−")


def _practice(label: str) -> dict | None:
    return PHENOMENON_PRACTICE.get(label)


def _phenomena_cell(w: dict) -> str:
    """The phenomena of a word as HTML: each with its link type, its provenance
    (* = form scoring) and whether to produce it or only recognize it."""
    variant = set(w.get("variant_labels") or ())
    parts = []
    for p in w["phenomena"]:
        name = PHENOMENON_LABEL.get(p, p)
        if p == "linking" and w.get("boundary_link_type") in LINK_TYPE_LABEL:
            name += f" ({LINK_TYPE_LABEL[w['boundary_link_type']]})"
        if p in variant:
            name += "*"
        # a known reduced form carries its own, more precise advice (below)
        practice = (None if p == "contraction_lex" and w.get("lexical_form") in LEXICAL_PRACTICE
                    else _practice(p))
        tag = (f' <span class="muted">· {_esc(PRACTICE_LABEL[practice["practice"]])}</span>'
               if practice else "")
        parts.append(_esc(name) + tag)
    if w.get("lexical_form"):
        form = w["lexical_form"]
        lex = f"“{_esc(form)}”"
        if w.get("lexical_expansion"):
            lex += f' <span class="muted">← {_esc(w["lexical_expansion"])}</span>'
        practice = LEXICAL_PRACTICE.get(form)
        if practice:
            lex += (f' <span class="muted">· {_esc(PRACTICE_LABEL[practice["practice"]])}, '
                    f'{_esc(practice["register"])}</span>')
        parts.append(lex)
    return ", ".join(parts) or "—"


def _form_row(form: dict) -> str | None:
    """The weak/strong scoring of a function word (espeak engine), in one line."""
    margin = form.get("weak_margin")
    if margin is None:
        return None
    kind = "weak" if form.get("weak") else "strong"
    row = (f'<span class="lbl">form scoring</span> {kind} form '
           f'<span class="ipa">[{_esc(form.get("ipa", ""))}]</span>, '
           f"margin {_signed(margin)}")
    if form.get("h_drop_margin") is not None:
        row += f" · h {'dropped' if form.get('h_dropped') else 'kept'} ({_signed(form['h_drop_margin'])})"
    return row


def _word_html(w: dict, emphasized: bool, prominence: float | None = None,
               word_class: str | None = None) -> str:
    fams = [FAMILY_OF[p] for p in w["phenomena"] if p in FAMILY_OF]
    fam = fams[0] if fams else None
    is_contr = "contraction_lex" in w["phenomena"]
    classes = ["w"]
    style = ""
    if fam:
        classes.append("hl")
        style = f"--fam: var(--fam-{fam}); --tint: var(--tint-{fam});"
    if is_contr:
        classes.append("contr")
    if emphasized:
        classes.append("emph")
    if w.get("low_confidence"):
        classes.append("lowconf")

    tip_rows = [
        f'<span class="lbl">dict.</span> <span class="ipa">/{_esc(w["dict_ipa"])}/</span>',
        f'<span class="lbl">canonical</span> <span class="ipa">[{_esc(w["canonical_ipa"])}]</span>',
        f'<span class="lbl">realized</span> <span class="ipa">[{_esc(w["realized_ipa"] or "∅")}]</span>',
    ]
    if w.get("realized_raw_ipa"):
        tip_rows.append(
            f'<span class="lbl">realized (raw)</span> <span class="ipa">[{_esc(w["realized_raw_ipa"])}]</span>'
        )
    if w.get("attracted_count"):
        tip_rows.append(
            f'<span class="lbl">{w["attracted_count"]} phone(s) attracted to the canonical form</span>'
        )
    if w.get("low_confidence"):
        tip_rows.append('<span class="lbl">low confidence (possibly silence/laughter)</span>')
    if w["phenomena"] or w.get("lexical_form"):
        tip_rows.append(f'<span class="lbl">phenomena</span> {_phenomena_cell(w)}')
    if w.get("variant_labels"):
        tip_rows.append('<span class="lbl">* from weak/strong form scoring, not from the '
                        "recognized phones</span>")
    if w.get("form"):
        row = _form_row(w["form"])
        if row:
            tip_rows.append(row)
    if prominence is not None:
        cls = f" · {_esc(word_class)} word" if word_class else ""
        tip_rows.append(f'<span class="lbl">prominence</span> {prominence:.2f}{cls}')
    if emphasized:
        tip_rows.append('<span class="lbl">emphasized word of the segment</span>')
    if w.get("oov"):
        tip_rows.append('<span class="lbl">out of dictionary (g2p)</span>')
    tip = f'<span class="tip">{"<br>".join(tip_rows)}</span>'

    chip = f'<span class="chip">{_esc(w["lexical_form"])}</span>' if w.get("lexical_form") else ""
    word = f'<span class="{" ".join(classes)}" style="{style}">{_esc(w["word"])}{tip}</span>{chip}'
    if w.get("boundary_link_next"):
        how = LINK_TYPE_LABEL.get(w.get("boundary_link_type") or "")
        title = f"linking: {how}" if how else "linking"
        word += f'<span class="tie" title="{_esc(title)}">‿</span>'
    else:
        word += " "
    return word


def _units_html(units: list[dict] | None) -> str:
    """Each intonation unit: its type, its final contour and the one its type
    calls for (report §4), with uptalk flagged. Words, not color, carry it."""
    if not units:
        return ""
    items = []
    for u in units:
        kind = UNIT_TYPE_LABEL.get(u.get("type"), u.get("type") or "")
        contour = _ARROW.get(u.get("final_contour"), u.get("final_contour") or "—")
        if u.get("final_slope_st") is not None:
            contour += f" ({_signed(u['final_slope_st'])} st/s)"
        expected = u.get("expected_contour")
        verdict = ""
        if expected:
            if u.get("matches_expected"):
                verdict = f" · ✓ as expected for a {kind}"
            else:
                verdict = f" · ✗ a {kind} is expected to end {_ARROW.get(expected, expected)}"
        uptalk = ' <span class="tag">uptalk</span>' if u.get("uptalk") else ""
        items.append(
            f'<li><span class="tag">{_esc(kind)}</span> '
            f'<span class="utext">“{_esc(u.get("text", ""))}”</span> '
            f"{_esc(contour)}{_esc(verdict)}{uptalk}</li>"
        )
    return f'<ul class="units">{"".join(items)}</ul>'


def _segment_html(seg: dict) -> str:
    f0 = seg["f0_stats"]
    stats = "F0 —"
    if f0["mean"] is not None:
        # too little voicing for a range (prosody.MIN_VOICED_FOR_RANGE_STATS)
        spread = (f"range {f0['range_st']:.1f} st" if f0.get("range_st") is not None
                  else f"range {f0['range']:.0f} Hz" if f0.get("range") is not None
                  else "range —")
        contour = _ARROW.get(f0["final_contour"], f0["final_contour"])
        if f0.get("final_slope_st") is not None:
            contour += f" ({_signed(f0['final_slope_st'])} st/s)"
        stats = f"mean F0 {f0['mean']:.0f} Hz · {spread} · {contour}"
    rhythm = seg.get("rhythm")
    if rhythm and rhythm.get("npvi") is not None:
        stats += f" · rhythm nPVI {rhythm['npvi']:.1f} (approximate)"

    prominence = seg.get("prominence") or []
    classes = seg.get("word_classes") or []
    words = seg["words"]

    def at(seq, i):
        return seq[i] if i < len(seq) else None

    words_html = "".join(
        _word_html(w, emphasized=(i == seg["emphasis_word_idx"]),
                   prominence=at(prominence, i), word_class=at(classes, i))
        for i, w in enumerate(words)
    )
    has_prom = bool(prominence)
    rows = []
    for i, w in enumerate(words):
        canon = " ".join(f"{p}" for p, _, _ in w["canonical_aligned"])
        real = " ".join(f"{p}" for p, _, _ in w["realized_aligned"]) or "∅"
        if w.get("realized_raw_ipa"):
            real = f'{w["realized_raw_ipa"]} → {real}'
        prom_td = ""
        if has_prom:
            p = at(prominence, i)
            cls = at(classes, i) or ""
            prom_td = (f'<td class="num"><span class="pbar" style="width:{round(p * 40)}px"></span>'
                       f'{p:.2f} <span class="muted">{_esc(cls)}</span></td>'
                       if p is not None else "<td>—</td>")
        rows.append(
            f'<tr><td class="t">{w["start"]:.2f}–{w["end"]:.2f}</td>'
            f"<td>{_esc(w['word'])}</td>"
            f'<td class="ipa">/{_esc(w["dict_ipa"])}/</td>'
            f'<td class="ipa">{_esc(canon)}</td>'
            f'<td class="ipa">{_esc(real)}</td>'
            f"<td>{_phenomena_cell(w)}</td>{prom_td}</tr>"
        )
    table = (
        '<details><summary>Word-by-word detail</summary><table>'
        "<tr><th>t (s)</th><th>word</th><th>dictionary</th>"
        "<th>aligned canonical</th><th>realized</th><th>phenomena</th>"
        + ("<th>prominence</th>" if has_prom else "") + "</tr>"
        + "".join(rows) + "</table></details>"
    )
    return f"""<div class="card">
  <div class="seg-head">
    <span class="seg-time">{seg["start"]:.2f}–{seg["end"]:.2f} s</span>
    <span class="f0meta">{_esc(stats)}</span>
  </div>
  <div class="text">{words_html}</div>
  {_units_html(seg.get("intonation_units"))}
  {_sparkline(seg["f0_track"], seg["start"], seg["end"])}
  {table}
</div>"""


def _summary_html(analysis: dict) -> str:
    counts = analysis["summary"]["phenomena_counts"]
    if not counts:
        return '<div class="card">No phenomena detected.</div>'
    mx = max(counts.values())
    rows = []
    for name, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        fam = FAMILY_OF.get(name)
        color = f"var(--fam-{fam})" if fam else "var(--ink-muted)"
        width = max(2, round(n / mx * 100))
        practice = _practice(name)
        tag = (f'<span class="tag" title="{_esc(practice["why"])}">'
               f'{_esc(PRACTICE_LABEL[practice["practice"]])}</span>' if practice else "")
        rows.append(
            f'<div class="sumrow"><span>{_esc(PHENOMENON_LABEL.get(name, name))}</span>'
            f'<div><div class="sumbar" style="width:{width}%;background:{color}"></div></div>'
            f'<span class="n">{n}</span><span>{tag}</span></div>'
        )
    return f'<div class="card"><strong>Phenomena detected</strong>{"".join(rows)}</div>'


def _meter(pct: float, ref: dict | None) -> str:
    """This clip's value (the ink tick) over the reference range (the band), on 0–100 %."""
    band = ""
    title = f"this clip {pct:.1f} %"
    if ref:
        low, high = ref["low"], ref["high"]
        if high is None:
            band = f'<span class="band" style="left:{low}%;width:{100 - low}%"></span>'
        elif high == low:
            band = f'<span class="band" style="left:{low}%;width:3px;margin-left:-1.5px"></span>'
        else:
            band = f'<span class="band" style="left:{low}%;width:{high - low}%"></span>'
        title += f" · reference {ref['display']} ({ref['cite']})"
    left = min(max(pct, 0.0), 100.0)
    return (f'<span class="meter" role="img" aria-label="{_esc(title)}" title="{_esc(title)}">'
            f'{band}<span class="val" style="left:{left}%"></span></span>')


def _metrics_html(analysis: dict) -> str:
    """How much of the clip is reduced, next to the conversational norm."""
    m = analysis["summary"].get("metrics") or metrics_mod.safe_compute(analysis)
    if not m:
        return ""
    rows = []
    for key in METRIC_ROWS:
        value = m.get(key)
        extra = ""
        if key == "weak_forms" and value:
            variant = value.get("variant")
            value = value.get("greedy")
            if variant:
                extra = (f'<div class="muted">form scoring: {variant["pct"]:.1f} % weak '
                         f'({variant["weak"]} weak · {variant["strong"]} strong · '
                         f'{variant["uncertain"]} uncertain)</div>')
        ref = metrics_mod.REFERENCE.get(key)
        label = metrics_mod.METRIC_LABELS.get(key, key)
        if not value or not value.get("of"):
            clip, meter = '<span class="muted">— (no context in this clip)</span>', ""
        else:
            clip = (f'{value["pct"]:.1f} % <span class="muted">({value["count"]} of '
                    f'{value["of"]})</span>')
            meter = _meter(value["pct"], ref)
        reference = (f'{_esc(ref["display"])} <span class="muted" title="{_esc(ref["source"])}">'
                     f'{_esc(ref["cite"])}</span>' if ref else '<span class="muted">—</span>')
        rows.append(f"<tr><td>{_esc(label)}{extra}</td><td class='num'>{clip}</td>"
                    f"<td>{meter}</td><td>{reference}</td></tr>")

    words = m.get("words") or {}
    engine = m.get("engine") or engine_of(analysis["meta"])
    head = (f'{words.get("analyzed", 0)} of {words.get("total", 0)} words compared · '
            f'{words.get("low_confidence_pct", 0):.1f} % low confidence · engine {engine}')
    notes = [
        "The references are rates over corpora of spontaneous American conversation; "
        "a scripted or animated clip is not expected to match them. The band is the "
        "reference, the tick is this clip.",
    ]
    if engine == "espeak":
        notes.append("The espeak engine " + ENGINE_NOTE["espeak"].split(": ", 1)[1])
    rhythm = m.get("rhythm")
    if rhythm and rhythm.get("npvi") is not None:
        varco = f" · Varco {rhythm['varco']:.1f}" if rhythm.get("varco") is not None else ""
        notes.append(
            f"Rhythm (approximate): nPVI {rhythm['npvi']:.1f}{varco} over "
            f"{rhythm['n_intervals']} intervals between syllable nuclei. They come from "
            "the recognizer's peak timings, not measured durations: compare clips with "
            "each other, not with published values."
        )
    return f"""<div class="card"><strong>How much of this clip is reduced</strong>
  <div class="note" style="margin-top:2px">{_esc(head)}</div>
  <table><tr><th>measure</th><th>this clip</th><th></th><th>reference</th></tr>
  {"".join(rows)}</table>
  {"".join(f'<p class="note">{_esc(n)}</p>' for n in notes)}
</div>"""


def _practice_table() -> str:
    """Every phenomenon with what the report advises: produce it or only recognize it."""
    rows = []
    order = [m for fam in FAMILY_MEMBERS for m in FAMILY_MEMBERS[fam]]
    order += [p for p in PHENOMENON_LABEL if p not in order]
    for p in order:
        practice = _practice(p)
        if not practice:
            continue
        fam = FAMILY_OF.get(p)
        dot = (f'<span class="dot" style="display:inline-block;margin-right:6px;'
               f'background:var(--fam-{fam})"></span>' if fam else "")
        desc = PHENOMENON_DESCRIPTION.get(p, "")
        rows.append(
            f"<tr><td>{dot}{_esc(PHENOMENON_LABEL.get(p, p))}"
            f'<div class="muted">{_esc(desc)}</div></td>'
            f'<td>{_esc(PRACTICE_LABEL[practice["practice"]])}</td>'
            f'<td>{_esc(practice["register"])}</td>'
            f'<td>{_esc(practice["why"])}</td></tr>'
        )
    return ('<details><summary>Produce it or only recognize it? — what the report '
            "advises for each phenomenon</summary><table>"
            "<tr><th>phenomenon</th><th>practice</th><th>register</th><th>why</th></tr>"
            + "".join(rows) + "</table></details>")


def _legend_html(has_variant_labels: bool = False) -> str:
    items = []
    for fam, label in FAMILY_LABEL.items():
        members = ", ".join(PHENOMENON_LABEL[m] for m in FAMILY_MEMBERS[fam])
        items.append(
            f'<div class="item"><span class="dot" style="background:var(--fam-{fam})"></span>'
            f"<span>{_esc(label)} <span class='members'>({_esc(members)})</span></span></div>"
        )
    marks = ("⋯ dotted underline = lexical contraction (“wanna”) · ‿ = linking (hover it: "
             "a consonant, an r or a w/j glide) · bold = emphasized word · "
             "dimmed = elided word / low confidence")
    if has_variant_labels:
        marks += " · * = label from weak/strong form scoring"
    items.append(f'<div class="item"><span class="marks">{_esc(marks)}</span></div>')
    return (f'<div class="card"><div class="legend">{"".join(items)}</div>'
            f"{_practice_table()}</div>")


def _provenance_html(meta: dict) -> str:
    """Which recognizer heard the phones, and what audio it heard."""
    engine = engine_of(meta)
    lines = [f"Phone engine {engine}: {ENGINE_NOTE.get(engine, '')}".rstrip(": ")]
    sep = meta.get("dialogue_separation")
    if sep:
        if sep.get("applied"):
            took = f", {sep['seconds']:.0f} s" if sep.get("seconds") is not None else ""
            lines.append(f"Dialogue separated from music and effects before the analysis "
                         f"({sep.get('model', 'htdemucs')}{took}); playback keeps the "
                         "original mix.")
        elif sep.get("error"):
            lines.append(f"Dialogue separation failed ({sep['error']}): the original mix "
                         "was analyzed.")
        else:
            lines.append("Dialogue separation off: the original mix was analyzed.")
    if meta.get("attraction"):
        lines.append("Phonetic attraction on: acoustic confusions were pulled toward the "
                     "canonical form (the tooltips show the raw phones).")
    return '<div class="prov">' + "".join(f"<div>{_esc(x)}</div>" for x in lines) + "</div>"


def render_html(analysis: dict) -> str:
    meta = analysis["meta"]
    segments = "".join(_segment_html(s) for s in analysis["segments"])
    has_variant = any(w.get("variant_labels")
                      for s in analysis["segments"] for w in s["words"])
    return f"""<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>PhonoTrainer — {_esc(meta["source"])}</title>
<style>{_CSS}</style>
<body class="viz-root">
<div class="wrap">
  <h1>PhonoTrainer</h1>
  <div class="meta">
    {_esc(meta["source"])} · {meta["duration"]:.1f} s · language: {_esc(meta["language"])}
    · {_esc(meta["models"]["asr"])} + {_esc(meta["models"]["phones"])}
    · v{_esc(meta["phonotrainer_version"])}
  </div>
  {_provenance_html(meta)}
  {_legend_html(has_variant)}
  {_metrics_html(analysis)}
  {_summary_html(analysis)}
  {segments}
</div>
<script>{_JS}</script>
</body>
</html>"""
