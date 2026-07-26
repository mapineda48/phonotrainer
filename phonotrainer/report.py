"""report.html autocontenido: transcripción coloreada por familia de fenómeno,
tooltips canónico vs real, sparkline SVG de F0 por segmento, tabla de detalle.

Colores: 4 slots categóricos validados (all-pairs, claro/oscuro) del sistema de
referencia; la contracción léxica se codifica sin color (subrayado punteado +
chip con la forma reducida). La identidad nunca es solo color: leyenda, tooltip
y tabla nombran cada fenómeno.
"""

from __future__ import annotations

import html
import json
import math

FAMILY_OF = {
    "vowel_reduction": "red",  # reducción
    "monophthongization": "red",
    "elision_syllable": "red",
    "flapping": "td",          # procesos de t/d
    "t_deletion": "td",
    "glottalization": "td",
    "th_stopping": "asim",     # asimilación
    "palatalization": "asim",
    "linking": "fron",         # frontera de palabra
    "h_dropping": "fron",
    # word_elision no colorea: se muestra atenuada (baja confianza)
}

FAMILY_LABEL = {
    "red": "Reducción",
    "td": "Procesos de t/d",
    "asim": "Asimilación",
    "fron": "Frontera de palabra",
}

FAMILY_MEMBERS = {
    "red": ["vowel_reduction", "monophthongization", "elision_syllable"],
    "td": ["flapping", "t_deletion", "glottalization"],
    "asim": ["th_stopping", "palatalization"],
    "fron": ["linking", "h_dropping"],
}

PHENOMENON_LABEL = {
    "vowel_reduction": "reducción vocálica",
    "monophthongization": "monoptongación",
    "elision_syllable": "sílaba elidida",
    "word_elision": "palabra elidida",
    "flapping": "flapping",
    "t_deletion": "t/d elidida",
    "glottalization": "glotalización",
    "th_stopping": "th-stopping",
    "palatalization": "palatalización",
    "linking": "linking",
    "h_dropping": "h muda",
    "contraction_lex": "contracción léxica",
}

# Qué es cada fenómeno, en una frase y con el ejemplo canónico de la tabla de
# reglas (task.md, fase 5). La interfaz los muestra junto a cada etiqueta: sin
# esto, «glotalización» no le dice nada a quien está aprendiendo.
PHENOMENON_DESCRIPTION = {
    "vowel_reduction": "Una vocal plena se reduce a schwa en sílaba átona. does → dəz",
    "monophthongization": "Un diptongo se realiza como vocal simple. my → ma",
    "elision_syllable": "Se pierde una sílaba entera. probably → prɒbli",
    "word_elision": "La palabra no deja rastro acústico: el habla rápida se la come.",
    "flapping": "/t/ o /d/ entre vocales suenan como una erre suave. water → wɔɾɚ",
    "t_deletion": "La /t/ o /d/ final no llega a pronunciarse. that → ðæ",
    "glottalization": "La /t/ se cierra en golpe de glotis. button → bʌʔn̩",
    "th_stopping": "θ y ð se pronuncian como oclusivas t/d. that → dat",
    "palatalization": "t+j y d+j se funden en tʃ/dʒ al final de palabra. got you → gotcha",
    "linking": "La consonante final se enlaza con la vocal siguiente. does it → dʌ‿zɪt",
    "h_dropping": "La h átona desaparece. tell him → tell im",
    "contraction_lex": "Forma reducida lexicalizada. want to → wanna",
}

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
  --fam-red: #2a78d6; --fam-td: #008300; --fam-asim: #e87ba4; --fam-fron: #eda100;
  --f0: #4a3aa7;
  --tint-red: rgba(42,120,214,.13); --tint-td: rgba(0,131,0,.12);
  --tint-asim: rgba(232,123,164,.16); --tint-fron: rgba(237,161,0,.15);
}
@media (prefers-color-scheme: dark) {
  .viz-root {
    --page: #0d0d0d; --surface: #1a1a19; --ink: #ffffff; --ink-2: #c3c2b7;
    --ink-muted: #898781; --grid: #2c2c2a; --baseline: #383835;
    --border: rgba(255,255,255,0.10);
    --fam-red: #3987e5; --fam-td: #008300; --fam-asim: #d55181; --fam-fron: #c98500;
    --f0: #9085e9;
    --tint-red: rgba(57,135,229,.22); --tint-td: rgba(0,131,0,.22);
    --tint-asim: rgba(213,81,129,.22); --tint-fron: rgba(201,133,0,.22);
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
.sumrow { display: grid; grid-template-columns: 170px 1fr 40px; gap: 10px;
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
     aria-label="Contorno de F0 del segmento, {lo:.0f} a {hi:.0f} Hz">
  <line class="axis" x1="{lab}" y1="{height - 6}" x2="{width - pad}" y2="{height - 6}"/>
  <line class="cross" x1="0" x2="0" y1="{pad}" y2="{height - 6}"/>
  <text x="{lab - 4}" y="{pad + 8}" text-anchor="end">{hi:.0f}</text>
  <text x="{lab - 4}" y="{height - 8}" text-anchor="end">{lo:.0f}</text>
  <polyline class="line" points="{poly}"/>
  <circle class="pt" cx="{lx}" cy="{ly}" r="2.5"/>
</svg>
<div class="sparktip"></div>
</div>"""


_ARROW = {"rising": "↗ ascendente", "falling": "↘ descendente", "flat": "→ plano"}


def _word_html(w: dict, emphasized: bool) -> str:
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
        f'<span class="lbl">dicc.</span> <span class="ipa">/{_esc(w["dict_ipa"])}/</span>',
        f'<span class="lbl">canónico</span> <span class="ipa">[{_esc(w["canonical_ipa"])}]</span>',
        f'<span class="lbl">real</span> <span class="ipa">[{_esc(w["realized_ipa"] or "∅")}]</span>',
    ]
    if w.get("realized_raw_ipa"):
        tip_rows.append(
            f'<span class="lbl">real (crudo)</span> <span class="ipa">[{_esc(w["realized_raw_ipa"])}]</span>'
        )
    if w.get("attracted_count"):
        tip_rows.append(
            f'<span class="lbl">{w["attracted_count"]} fono(s) atraído(s) al canónico</span>'
        )
    if w.get("low_confidence"):
        tip_rows.append('<span class="lbl">baja confianza (posible silencio/risas)</span>')
    if w["phenomena"]:
        names = ", ".join(PHENOMENON_LABEL.get(p, p) for p in w["phenomena"])
        tip_rows.append(f'<span class="lbl">fenómenos</span> {_esc(names)}')
    if w.get("lexical_form"):
        tip_rows.append(f'<span class="lbl">forma reducida</span> “{_esc(w["lexical_form"])}”')
    if emphasized:
        tip_rows.append('<span class="lbl">énfasis del segmento</span>')
    if w.get("oov"):
        tip_rows.append('<span class="lbl">fuera de diccionario (g2p)</span>')
    tip = f'<span class="tip">{"<br>".join(tip_rows)}</span>'

    chip = f'<span class="chip">{_esc(w["lexical_form"])}</span>' if w.get("lexical_form") else ""
    word = f'<span class="{" ".join(classes)}" style="{style}">{_esc(w["word"])}{tip}</span>{chip}'
    if w.get("boundary_link_next"):
        word += '<span class="tie">‿</span>'
    else:
        word += " "
    return word


def _segment_html(seg: dict) -> str:
    f0 = seg["f0_stats"]
    stats = "F0 —"
    if f0["mean"] is not None:
        stats = (f"F0 media {f0['mean']:.0f} Hz · rango {f0['range']:.0f} Hz · "
                 f"{_ARROW.get(f0['final_contour'], f0['final_contour'])}")
    words_html = "".join(
        _word_html(w, emphasized=(i == seg["emphasis_word_idx"]))
        for i, w in enumerate(seg["words"])
    )
    rows = []
    for w in seg["words"]:
        canon = " ".join(f"{p}" for p, _, _ in w["canonical_aligned"])
        real = " ".join(f"{p}" for p, _, _ in w["realized_aligned"]) or "∅"
        if w.get("realized_raw_ipa"):
            real = f'{w["realized_raw_ipa"]} → {real}'
        phen = ", ".join(PHENOMENON_LABEL.get(p, p) for p in w["phenomena"]) or "—"
        rows.append(
            f'<tr><td class="t">{w["start"]:.2f}–{w["end"]:.2f}</td>'
            f"<td>{_esc(w['word'])}</td>"
            f'<td class="ipa">/{_esc(w["dict_ipa"])}/</td>'
            f'<td class="ipa">{_esc(canon)}</td>'
            f'<td class="ipa">{_esc(real)}</td>'
            f"<td>{_esc(phen)}</td></tr>"
        )
    table = (
        '<details><summary>Detalle por palabra</summary><table>'
        "<tr><th>t (s)</th><th>palabra</th><th>diccionario</th>"
        "<th>canónico alineado</th><th>real</th><th>fenómenos</th></tr>"
        + "".join(rows) + "</table></details>"
    )
    return f"""<div class="card">
  <div class="seg-head">
    <span class="seg-time">{seg["start"]:.2f}–{seg["end"]:.2f} s</span>
    <span class="f0meta">{_esc(stats)}</span>
  </div>
  <div class="text">{words_html}</div>
  {_sparkline(seg["f0_track"], seg["start"], seg["end"])}
  {table}
</div>"""


def _summary_html(analysis: dict) -> str:
    counts = analysis["summary"]["phenomena_counts"]
    if not counts:
        return '<div class="card">Sin fenómenos detectados.</div>'
    mx = max(counts.values())
    rows = []
    for name, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        fam = FAMILY_OF.get(name)
        color = f"var(--fam-{fam})" if fam else "var(--ink-muted)"
        width = max(2, round(n / mx * 100))
        rows.append(
            f'<div class="sumrow"><span>{_esc(PHENOMENON_LABEL.get(name, name))}</span>'
            f'<div><div class="sumbar" style="width:{width}%;background:{color}"></div></div>'
            f'<span class="n">{n}</span></div>'
        )
    return f'<div class="card"><strong>Fenómenos detectados</strong>{"".join(rows)}</div>'


def _legend_html() -> str:
    items = []
    for fam, label in FAMILY_LABEL.items():
        members = ", ".join(PHENOMENON_LABEL[m] for m in FAMILY_MEMBERS[fam])
        items.append(
            f'<div class="item"><span class="dot" style="background:var(--fam-{fam})"></span>'
            f"<span>{_esc(label)} <span class='members'>({_esc(members)})</span></span></div>"
        )
    items.append('<div class="item"><span class="marks">⋯ subrayado punteado = contracción léxica'
                 " (“wanna”) · ‿ = linking · negrita = palabra enfatizada · "
                 "atenuada = palabra elidida / baja confianza</span></div>")
    return f'<div class="card"><div class="legend">{"".join(items)}</div></div>'


def render_html(analysis: dict) -> str:
    meta = analysis["meta"]
    segments = "".join(_segment_html(s) for s in analysis["segments"])
    return f"""<!doctype html>
<html lang="es">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>PhonoTrainer — {_esc(meta["source"])}</title>
<style>{_CSS}</style>
<body class="viz-root">
<div class="wrap">
  <h1>PhonoTrainer</h1>
  <div class="meta">
    {_esc(meta["source"])} · {meta["duration"]:.1f} s · idioma: {_esc(meta["language"])}
    · {_esc(meta["models"]["asr"])} + {_esc(meta["models"]["phones"])}
    · v{_esc(meta["phonotrainer_version"])}
  </div>
  {_legend_html()}
  {_summary_html(analysis)}
  {segments}
</div>
<script>{_JS}</script>
</body>
</html>"""
