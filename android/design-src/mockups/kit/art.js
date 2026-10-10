// Phonautograph illustration set (reference compositions for the ILLUSTRATIONS work order).
// Style "engraved ink": 2.5 to 3 dp ink monoline with round caps, kraft flat fills, halftone for light,
// hatching at 30 and -35 degrees for shade (never the 45/135 degree lines or dots that mean families in charts),
// and the stylus trace as narrator. Only ink, paper and kraft tokens: dark mode swaps them, never a family hue.
(function () {
  const S = (w, h, body, extra = "") => `<svg aria-hidden="true" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" fill="none" stroke-linecap="round" stroke-linejoin="round" ${extra}>${body}</svg>`;
  let uid = 0;
  const hatch = (angle, gap = 6, w = 1.2, op = .55) => { const id = "h" + (++uid); return { id, def: `<pattern id="${id}" width="${gap}" height="${gap}" patternUnits="userSpaceOnUse" patternTransform="rotate(${angle})"><line x1="0" y1="0" x2="0" y2="${gap}" stroke="var(--ink)" stroke-width="${w}" stroke-opacity="${op}"/></pattern>` }; };
  function dots(x0, y0, w, h, seed, cell = 6, density = 1) {
    const r = window.rng(seed); let s = "";
    for (let x = x0 + cell / 2; x < x0 + w; x += cell) for (let y = y0 + cell / 2; y < y0 + h; y += cell) {
      const cx = (x - x0) / w - .5, cy = (y - y0) / h - .5; const fall = Math.max(0, 1 - Math.hypot(cx * 1.6, cy * 1.6));
      const rad = cell * .42 * Math.sqrt(fall * density * (.55 + .45 * r()));
      if (rad > .5) s += `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${rad.toFixed(2)}"/>`;
    }
    return `<g fill="var(--halftone-strong)">${s}</g>`;
  }
  const ground = (x1, x2, y, hid) => `<path d="M${x1} ${y}H${x2}" stroke="var(--ink)" stroke-width="3"/><rect x="${x1 + 8}" y="${y + 3}" width="${x2 - x1 - 16}" height="12" fill="url(#${hid})"/><path d="M${x1 + 8} ${y + 15}H${x2 - 8}" stroke="var(--ink)" stroke-width="1.5"/>`;
  const wave = (x, y, len, amp, n, sw = 3, decay = 0) => { let d = `M${x} ${y}`; const step = len / n; for (let i = 0; i < n; i++) { const a = amp * Math.pow(1 - decay * i / n, 1.4) * (i % 2 ? 1 : -1); d += ` Q${(x + step * (i + .5)).toFixed(1)} ${(y + a * 2).toFixed(1)} ${(x + step * (i + 1)).toFixed(1)} ${y}`; } return `<path d="${d}" stroke="var(--ink)" stroke-width="${sw}"/>`; };
  const dotted = (x1, y1, x2, y2, gap = 7, r = 1.4) => { const n = Math.max(1, Math.round(Math.hypot(x2 - x1, y2 - y1) / gap)); let s = ""; for (let i = 0; i <= n; i++) s += `<circle cx="${(x1 + (x2 - x1) * i / n).toFixed(1)}" cy="${(y1 + (y2 - y1) * i / n).toFixed(1)}" r="${r}" fill="var(--ink)"/>`; return s; };
  const monitor = (x, y, w, h, face) => `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="6" fill="var(--ink)" stroke="var(--ink)" stroke-width="3"/>${face || ""}<path d="M${x + w / 2 - 12} ${y + h}L${x + w / 2 - 16} ${y + h + 26}H${x + w / 2 + 16}L${x + w / 2 + 12} ${y + h}" fill="var(--kraft)" stroke="var(--ink)" stroke-width="2.5"/><path d="M${x + w / 2 - 28} ${y + h + 38}Q${x + w / 2} ${y + h + 24} ${x + w / 2 + 28} ${y + h + 38}" fill="var(--kraft)" stroke="var(--ink)" stroke-width="2.5"/>`;
  const phone = (x, y, rot, screen) => `<g transform="translate(${x} ${y}) rotate(${rot})"><rect x="0" y="0" width="58" height="104" rx="10" fill="var(--sheet)" stroke="var(--ink)" stroke-width="3"/><rect x="6" y="12" width="46" height="78" rx="3" fill="var(--kraft)" fill-opacity=".55"/>${screen || ""}<circle cx="29" cy="97" r="2.5" fill="var(--ink)"/></g>`;
  const slip = (x, y, w, h, rot, inner = "") => `<g transform="translate(${x} ${y}) rotate(${rot})"><rect x="0" y="0" width="${w}" height="${h}" rx="4" fill="var(--sheet)" stroke="var(--ink)" stroke-width="2.5"/>${inner}</g>`;
  const mug = (x, y) => `<path d="M${x} ${y}h26v20a6 6 0 0 1-6 6h-14a6 6 0 0 1-6-6z" fill="var(--sheet)" stroke="var(--ink)" stroke-width="2.5"/><path d="M${x + 26} ${y + 6}a7 7 0 0 1 0 12" stroke="var(--ink)" stroke-width="2.5"/><path d="M${x + 8} ${y - 8}q-4-6 0-12M${x + 16} ${y - 8}q-4-6 0-12" stroke="var(--ink)" stroke-width="1.8" stroke-opacity=".6"/>`;

  const ART = {};
  // No computer answered (F-02 empty, Connection details): the wave leaves the phone, decays and goes flat before a sleeping monitor.
  ART.noComputer = (o = {}) => { const h1 = hatch(30), h2 = hatch(-35, 5, 1, .35); const W = o.w || 380;
    return S(W, 214, `<defs>${h1.def}${h2.def}</defs>${dots(150, 20, 220, 150, 7, 7, .9)}${ground(6, W - 6, 176, h1.id)}
      ${phone(44, 70, -6, `<path d="M10 52C14 36 18 66 23 50S31 40 35 52S43 58 48 50" stroke="var(--ink)" stroke-width="2.6"/>`)}
      <path d="M58 176L70 160H98L104 176" fill="var(--kraft)" stroke="var(--ink)" stroke-width="2.5"/>
      <path d="M100 46A30 30 0 0 1 116 66" stroke="var(--ink)" stroke-width="2.5"/>${dotted(106, 30, 130, 64, 6, 1.2)}
      <path d="M112 112C120 84 126 136 134 104S146 88 152 112S164 122 170 108S182 104 188 112S198 114 204 110" stroke="var(--ink)" stroke-width="3.2"/>${dotted(210, 110, 252, 110, 7, 1.5)}
      ${monitor(262, 58, 104, 80, `<rect x="270" y="66" width="88" height="62" rx="2" fill="url(#${h2.id})"/><path d="M296 98Q304 106 312 98M318 98Q326 106 334 98" stroke="var(--on-ink)" stroke-width="2.6"/><path d="M300 104l-3 5M308 104l2 5M322 104l-3 5M330 104l2 5" stroke="var(--on-ink)" stroke-width="2"/>`)}
      <path d="M338 40h10l-10 12h10M352 26h7l-7 9h7" stroke="var(--ink)" stroke-width="2"/>
      ${mug(206, 150)}<path d="M226 84q0-10 8-10t8 8q0 6-8 8v6M234 104v1" stroke="var(--ink)" stroke-width="2.6"/>`); };

  // Home first run / Welcome 2: the phone hands the computer an envelope with a wave on it; the computer reads it.
  ART.handover = (o = {}) => { const h1 = hatch(30), h2 = hatch(-35, 5, 1, .35); const W = o.w || 380;
    return S(W, 214, `<defs>${h1.def}${h2.def}</defs>${dots(10, 10, 360, 160, 12, 8, .55)}${ground(6, W - 6, 176, h1.id)}
      ${phone(40, 66, -8, `<path d="M14 40h30M14 50h22M14 60h26" stroke="var(--ink)" stroke-width="2.2" stroke-opacity=".7"/>`)}
      <path d="M54 176L66 160H94L100 176" fill="var(--kraft)" stroke="var(--ink)" stroke-width="2.5"/>
      <path d="M110 120C150 120 160 70 205 70" stroke="var(--ink)" stroke-width="3"/>
      <g transform="translate(150 62) rotate(-10)"><rect x="0" y="0" width="64" height="42" rx="3" fill="var(--kraft)" stroke="var(--ink)" stroke-width="2.6"/><path d="M0 2L32 24L64 2" stroke="var(--ink)" stroke-width="2.4"/><path d="M14 34c4-8 7 6 11-1s6-4 9 1 6 2 9-2 5 0 7 2" stroke="var(--ink)" stroke-width="2"/></g>
      ${monitor(250, 54, 112, 84, `<rect x="258" y="62" width="96" height="66" rx="2" fill="var(--sheet)"/>${dots(258, 62, 96, 66, 5, 5, 1.2)}<path d="M262 112C272 92 280 120 290 100S306 86 314 104S330 116 350 96" stroke="var(--ink)" stroke-width="2.6"/>`)}
      <path d="M118 150l10-6-10-6" stroke="var(--ink)" stroke-width="2.2"/>`); };

  // Welcome 1: a phonautograph horn collects a voice; the stylus writes it as a trace on ruled paper.
  ART.horn = (o = {}) => { const h1 = hatch(30), h2 = hatch(-35, 5, 1.1, .45); const W = o.w || 380;
    return S(W, 214, `<defs>${h1.def}${h2.def}</defs>${ground(6, W - 6, 176, h1.id)}
      <path d="M20 58L118 92L118 112L20 146Z" fill="var(--kraft)" stroke="var(--ink)" stroke-width="3"/><path d="M20 58Q4 102 20 146" fill="url(#${h2.id})" stroke="var(--ink)" stroke-width="3"/>
      <path d="M44 66v72M70 75v54M94 84v36" stroke="var(--ink)" stroke-width="1.6" stroke-opacity=".55"/>
      <rect x="118" y="88" width="16" height="28" rx="3" fill="var(--sheet)" stroke="var(--ink)" stroke-width="2.6"/><path d="M134 102H160L170 116" stroke="var(--ink)" stroke-width="2.6"/>
      <rect x="150" y="116" width="216" height="52" rx="3" fill="var(--sheet)" stroke="var(--ink)" stroke-width="2.6"/>
      <path d="M156 128H360M156 140H360M156 152H360" stroke="var(--ink)" stroke-width="1" stroke-opacity=".25"/>
      <path d="M170 140C180 118 188 160 198 136S214 120 222 144S236 152 246 130S262 124 270 142" stroke="var(--ink)" stroke-width="3.2"/>${dotted(278, 140, 352, 140, 7, 1.4)}
      <circle cx="170" cy="116" r="3.5" fill="var(--ink)"/>
      <path d="M4 84q8 18 0 36M10 76q14 26 0 52" stroke="var(--ink)" stroke-width="2.2" stroke-opacity=".7"/>${dots(160, 14, 200, 90, 13, 7, .7)}`); };

  // Welcome 3: today's set, a short stack of slips tied with the trace, and a small hourglass.
  ART.setStack = (o = {}) => { const h1 = hatch(30); const W = o.w || 380; let s = "";
    for (let i = 0; i < 5; i++) s += slip(96 + i * 6, 54 + i * 5, 150, 96, -8 + i * 3.4, i === 4 ? `<path d="M18 36c8-16 14 14 22-2s12-8 18 2 10 4 16-2" stroke="var(--ink)" stroke-width="2.6"/><path d="M18 70h80M18 80h56" stroke="var(--ink)" stroke-width="2" stroke-opacity=".5"/><path d="M0 0" />` : "");
    return S(W, 214, `<defs>${h1.def}</defs>${dots(220, 20, 150, 120, 4, 7, .8)}${ground(6, W - 6, 176, h1.id)}${s}
      <path d="M80 112C120 150 230 160 300 120" stroke="var(--ink)" stroke-width="2.6"/>
      <g transform="translate(300 96)"><path d="M0 0h34M0 64h34M4 0v10q0 12 13 22q13 10 13 22v10M30 0v10q0 12-13 22q-13 10-13 22v10" stroke="var(--ink)" stroke-width="2.6" fill="var(--sheet)"/><path d="M10 52h14l-7-8z" fill="var(--kraft)" stroke="var(--ink)" stroke-width="1.6"/></g>
      <g transform="translate(40 120)"><circle cx="0" cy="0" r="16" fill="var(--sheet)" stroke="var(--ink)" stroke-width="2.6"/><path d="M-7 0l5 5 9-10" stroke="var(--ink)" stroke-width="2.8"/></g>`); };

  // Watch first run: an empty screen on a stand; the trace waits as a dotted line.
  ART.feedEmpty = (o = {}) => { const h1 = hatch(30), h2 = hatch(-35, 6, 1, .3); const W = o.w || 380;
    return S(W, 214, `<defs>${h1.def}${h2.def}</defs>${ground(6, W - 6, 176, h1.id)}
      <rect x="110" y="20" width="160" height="128" rx="12" fill="var(--sheet)" stroke="var(--ink)" stroke-width="3"/><rect x="122" y="34" width="136" height="76" rx="4" fill="url(#${h2.id})" stroke="var(--ink)" stroke-width="1.6"/>
      ${dotted(130, 128, 250, 128, 8, 1.6)}<circle cx="130" cy="128" r="5" fill="var(--ink)"/>
      <path d="M176 60l30 12-30 12z" fill="var(--kraft)" stroke="var(--ink)" stroke-width="2.6"/>
      <path d="M170 148l-10 28M210 148l10 28" stroke="var(--ink)" stroke-width="3"/>
      <path d="M300 60c10 0 10 14 20 14s10-14 20-14" stroke="var(--ink)" stroke-width="2.2" stroke-opacity=".6"/><path d="M40 92c10 0 10 14 20 14s10-14 20-14" stroke="var(--ink)" stroke-width="2.2" stroke-opacity=".6"/>`); };

  // Home, paired but no clips: an empty phonautograph drum, rules only.
  ART.emptyDrum = (o = {}) => { const h1 = hatch(30), h2 = hatch(-35, 5, 1, .4); const W = o.w || 380;
    return S(W, 214, `<defs>${h1.def}${h2.def}</defs>${ground(6, W - 6, 176, h1.id)}
      <ellipse cx="120" cy="92" rx="16" ry="46" fill="var(--kraft)" stroke="var(--ink)" stroke-width="3"/><path d="M120 46H270M120 138H270" stroke="var(--ink)" stroke-width="3"/>
      <path d="M270 46A16 46 0 0 1 270 138" stroke="var(--ink)" stroke-width="3" fill="url(#${h2.id})"/>
      <path d="M134 64H262M136 80H266M136 96H266M136 112H264M134 126H260" stroke="var(--ink)" stroke-width="1.2" stroke-opacity=".35"/>
      <path d="M286 92H330" stroke="var(--ink)" stroke-width="5"/><circle cx="336" cy="92" r="8" fill="var(--sheet)" stroke="var(--ink)" stroke-width="3"/>
      <path d="M100 138L92 176M140 138L148 176M250 138L242 176M290 138L298 176" stroke="var(--ink)" stroke-width="3"/>
      <path d="M200 30V12l-8 8M200 12l8 8" stroke="var(--ink)" stroke-width="2.4"/>`); };

  // Requests empty: an empty in-tray and a quiet, flat trace.
  ART.inTray = (o = {}) => { const h1 = hatch(30), h2 = hatch(-35, 5, 1, .4); const W = o.w || 380;
    return S(W, 214, `<defs>${h1.def}${h2.def}</defs>${ground(6, W - 6, 176, h1.id)}
      <path d="M110 112L130 150H250L270 112" fill="var(--kraft)" stroke="var(--ink)" stroke-width="3"/><path d="M110 112H160L170 126H210L220 112H270" stroke="var(--ink)" stroke-width="3"/>
      <path d="M130 150V170H250V150" fill="url(#${h2.id})" stroke="var(--ink)" stroke-width="3"/>
      <path d="M40 70H120" stroke="var(--ink)" stroke-width="3"/>${dotted(128, 70, 340, 70, 9, 1.5)}<circle cx="40" cy="70" r="5" fill="var(--ink)"/>
      <path d="M300 40l12 12M312 40l-12 12" stroke="var(--ink)" stroke-width="2" stroke-opacity=".5"/>`); };

  // Learn, no examples: a magnifier over a flat line.
  ART.magnifier = (o = {}) => { const h1 = hatch(30), h2 = hatch(-35, 5, 1, .35); const W = o.w || 380;
    return S(W, 214, `<defs>${h1.def}${h2.def}</defs>${ground(6, W - 6, 176, h1.id)}
      <path d="M30 130H350" stroke="var(--ink)" stroke-width="2.6"/>${dotted(30, 130, 30, 130)}
      <circle cx="200" cy="96" r="46" fill="var(--sheet)" stroke="var(--ink)" stroke-width="3.2"/><circle cx="200" cy="96" r="46" fill="url(#${h2.id})" fill-opacity=".5"/>
      <path d="M160 130H240" stroke="var(--ink)" stroke-width="4"/><path d="M234 130l40 40" stroke="var(--ink)" stroke-width="9"/><path d="M234 130l40 40" stroke="var(--kraft)" stroke-width="4"/>
      <path d="M178 70q10-10 22-10" stroke="var(--ink)" stroke-width="2.2" stroke-opacity=".6"/>`); };

  // Practice, nothing yet: an ear with a dotted trace waiting to be filled.
  ART.ear = (o = {}) => { const h1 = hatch(30), h2 = hatch(-35, 5, 1, .4); const W = o.w || 380;
    return S(W, 214, `<defs>${h1.def}${h2.def}</defs>${ground(6, W - 6, 176, h1.id)}
      <path d="M150 140c-6 18 4 30 20 30 20 0 22-22 34-34 16-16 30-30 30-58 0-34-28-58-60-58s-58 24-58 56" fill="var(--kraft)" stroke="var(--ink)" stroke-width="3.2"/>
      <path d="M150 76c0-18 14-30 30-30s30 12 30 30c0 14-10 20-18 26-8 6-10 14-10 20" stroke="var(--ink)" stroke-width="3"/><path d="M178 96c-8 0-12-6-12-12" stroke="var(--ink)" stroke-width="2.6"/>
      <path d="M116 78c0 0 2 30 30 34" fill="url(#${h2.id})" stroke="none"/>
      ${dotted(250, 92, 350, 92, 8, 1.6)}<path d="M258 70c8 6 8 38 0 44M276 60c12 10 12 54 0 64" stroke="var(--ink)" stroke-width="2.4" stroke-opacity=".7"/>`); };

  // Saved empty: a bookmark ribbon over a blank slip.
  ART.bookmark = (o = {}) => { const h1 = hatch(30); const W = o.w || 380;
    return S(W, 214, `<defs>${h1.def}</defs>${ground(6, W - 6, 176, h1.id)}
      ${slip(110, 40, 170, 120, -4, `<path d="M20 34h90M20 50h120M20 66h70" stroke="var(--ink)" stroke-width="2" stroke-opacity=".25"/>`)}
      <path d="M222 30V116L240 100L258 116V30Z" fill="var(--kraft)" stroke="var(--ink)" stroke-width="3"/>${dotted(130, 140, 250, 134, 9, 1.4)}`); };

  // Today's set done (summary card spot): a stamped slip on the stack.
  ART.setDone = (o = {}) => { const W = o.w || 200, H = o.h || 120; let s = "";
    for (let i = 0; i < 3; i++) s += slip(36 + i * 5, 22 + i * 4, 110, 70, -6 + i * 4, i === 2 ? `<path d="M14 26c6-12 10 10 16-2s9-6 13 2 8 3 12-2" stroke="var(--ink)" stroke-width="2.4"/><path d="M14 50h60" stroke="var(--ink)" stroke-width="2" stroke-opacity=".4"/>` : "");
    return S(W, H, `${dots(110, 6, 90, 90, 21, 6, .9)}${s}<g transform="translate(140 72) rotate(-12)"><circle r="22" fill="var(--sheet)" stroke="var(--ink)" stroke-width="3"/><circle r="16" stroke="var(--ink)" stroke-width="1.4"/><path d="M-8 0l5 6 11-12" stroke="var(--ink)" stroke-width="3.4"/></g>`); };

  // Spot illustrations for everyday cards (Practice card, Insights teaser): richness beyond empty states.
  ART.spotPractice = (o = {}) => S(96, 72, `${dots(40, 2, 56, 50, 31, 5, 1)}<path d="M30 52c-3 9 2 15 10 15 10 0 11-11 17-17 8-8 15-15 15-29 0-17-14-29-30-29S13 4 13 20" fill="var(--kraft)" stroke="var(--ink)" stroke-width="2.6" transform="translate(0 2)"/><path d="M30 22c0-9 7-15 15-15s15 6 15 15c0 7-5 10-9 13s-5 7-5 10" stroke="var(--ink)" stroke-width="2.4" transform="translate(0 2)"/><circle cx="82" cy="34" r="10" stroke="var(--ink)" stroke-width="2.4" fill="var(--sheet)"/><circle cx="82" cy="34" r="4" fill="var(--ink)"/>`);
  ART.spotInsights = (o = {}) => { const h = hatch(30, 4, 1.1, .6); return S(96, 72, `<defs>${h.def}</defs><path d="M6 66H90" stroke="var(--ink)" stroke-width="2.4"/><rect x="12" y="38" width="14" height="28" fill="var(--kraft)" stroke="var(--ink)" stroke-width="2.2"/><rect x="32" y="20" width="14" height="46" fill="url(#${h.id})" stroke="var(--ink)" stroke-width="2.2"/><rect x="52" y="30" width="14" height="36" fill="var(--sheet)" stroke="var(--ink)" stroke-width="2.2"/><path d="M8 26H90" stroke="var(--ink)" stroke-width="1.4" stroke-dasharray="2 4"/><circle cx="76" cy="20" r="11" fill="var(--sheet)" stroke="var(--ink)" stroke-width="2.4"/><path d="M84 28l8 8" stroke="var(--ink)" stroke-width="3.4"/>`); };

  // The mouth, side view (sound card). Anatomy in the web's tract palette: never a family hue.
  ART.mouth = (o = {}) => S(o.w || 112, o.h || 104, `<rect x="0" y="0" width="112" height="104" rx="10" fill="var(--tract-air)"/>
    <path d="M24 104V52C24 38 30 30 40 28" stroke="var(--tract-line)" stroke-width="1.6"/>
    <path d="M72 2C80 8 84 16 86 24L100 36L91 41L95 47L90 52L95 56L89 64L93 78C88 88 80 94 66 98L62 104" fill="var(--tract-tissue)" fill-opacity=".45" stroke="var(--tract-line)" stroke-width="2"/>
    <path d="M86 45C80 38 70 33 58 32C46 31 38 34 32 40L30 50C34 46 38 43 44 41" fill="var(--tract-tissue)" stroke="var(--tract-line)" stroke-width="1.6"/>
    <path d="M84 44L88 44L87 51L84 50Z" fill="var(--tract-bone)" stroke="var(--tract-line)" stroke-width="1.1"/>
    <path d="M82 58L86 58L86 64L82 64Z" fill="var(--tract-bone)" stroke="var(--tract-line)" stroke-width="1.1"/>
    <path d="M30 96C27 80 28 64 36 54C44 46 56 44 66 45C72 45 77 46 81 47C78 51 74 54 68 57C60 61 56 70 56 82L56 96Z" fill="var(--tract-tongue)" stroke="var(--tract-line)" stroke-width="1.6"/>
    <path d="M91 48C95 50 95 54 91 56" fill="var(--tract-lip)" stroke="var(--tract-line)" stroke-width="1.3"/>
    <circle cx="81" cy="47" r="3" fill="var(--ink)" stroke="var(--tract-air)" stroke-width="1.5"/><path d="M82 45L100 16" stroke="var(--ink)" stroke-width="1.2"/>`);

  // Poster fallback (a composable in the app): seeded halftone on paper, crop marks, the title, the kind.
  ART.poster = (o = {}) => { const W = o.w || 176, H = o.h || 110; const r = window.rng(o.seed || 5);
    return `<div style="position:relative;width:100%;height:100%;background:var(--galley);overflow:hidden;border-radius:8px">${window.halftone({ w: W, h: H, seed: o.seed || 5, cell: 6, color: "var(--halftone-strong)" })}
      <span style="position:absolute;left:8px;top:8px;width:10px;height:10px;border-left:1px solid var(--ink);border-top:1px solid var(--ink);opacity:.5"></span><span style="position:absolute;right:8px;bottom:8px;width:10px;height:10px;border-right:1px solid var(--ink);border-bottom:1px solid var(--ink);opacity:.5"></span>
      <span style="position:absolute;left:12px;right:12px;bottom:10px;font:600 15px/1.15 var(--font-display);color:var(--ink);background:var(--galley);padding:2px 4px;border-radius:3px;width:max-content;max-width:calc(100% - 24px)">${o.title || ""}</span></div>`; };

  window.ART = ART;
})();
