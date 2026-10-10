// Phonautograph mockup kit: theme switch, icons, the stylus trace, the spectrogram halftone, system chrome, the mark.
// Everything here is a reference drawing for android/docs/design.md; the app draws the same things in Compose.
(function () {
  const q = new URLSearchParams(location.search);
  const root = document.documentElement;
  root.dataset.theme = q.get("theme") || "light";
  root.dataset.palette = q.get("palette") || root.dataset.palette || "standard";
  root.dataset.patterns = q.get("patterns") || root.dataset.patterns || (root.dataset.palette === "cvd" ? "on" : "off");
  if (q.get("scale")) root.style.setProperty("--fs", q.get("scale"));

  function rng(seed) { let a = seed >>> 0; return () => { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
  window.rng = rng;

  function icon(name, size = 20, sw = 2, cls = "") {
    const body = (window.ICONS || {})[name];
    if (body == null) console.warn("missing icon", name);
    return `<svg class="ico ${cls}" aria-hidden="true" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round">${body || ""}</svg>`;
  }
  window.icon = icon;
  const FAM_ICON = { reduction: "chevrons-down", td: "scissors", assimilation: "merge", boundary: "link-2", lexical: "shrink", other: "circle-dashed" };
  window.FAM_ICON = FAM_ICON;
  window.famIcon = (fam, size = 18, colored = true) => icon(FAM_ICON[fam], size, 2.25, colored && !["lexical", "other"].includes(fam) ? "c-" + fam : "");

  // A pitch contour: declination + accents + unvoiced gaps, deterministic per seed (stand-in for f0_track).
  function contour(seed, n, opts = {}) {
    const r = rng(seed); const pts = []; const accents = []; const k = opts.accents || 4;
    for (let i = 0; i < k; i++) accents.push({ c: (i + .3 + r() * .5) / k, a: .25 + r() * .55, s: .025 + r() * .04 });
    const gaps = opts.gaps || [];
    for (let i = 0; i <= n; i++) {
      const x = i / n; let y = .62 - .28 * x;
      for (const ac of accents) y -= ac.a * .5 * Math.exp(-((x - ac.c) ** 2) / (2 * ac.s ** 2));
      y += Math.sin(x * 61 + seed) * .012 + Math.sin(x * 23 + seed * 2) * .02;
      const amp = .35 + .65 * Math.abs(Math.sin(x * Math.PI * (k + .5) + r() * .2));
      pts.push({ x, y: Math.min(.92, Math.max(.08, y)), w: amp, voiced: !gaps.some(([a, b]) => x > a && x < b) });
    }
    return pts;
  }
  window.contour = contour;
  function ribbon(pts, W, H, wMin, wMax) {
    if (pts.length < 2) return "";
    const top = [], bot = [];
    for (let i = 0; i < pts.length; i++) {
      const p = pts[i], a = pts[Math.max(0, i - 1)], b = pts[Math.min(pts.length - 1, i + 1)];
      const dx = (b.x - a.x) * W, dy = (b.y - a.y) * H; const len = Math.hypot(dx, dy) || 1;
      const nx = -dy / len, ny = dx / len; const hw = (wMin + (wMax - wMin) * p.w) / 2;
      top.push([p.x * W + nx * hw, p.y * H + ny * hw]); bot.push([p.x * W - nx * hw, p.y * H - ny * hw]);
    }
    return "M" + top.concat(bot.reverse()).map(([x, y]) => x.toFixed(1) + " " + y.toFixed(1)).join("L") + "Z";
  }
  function runs(pts) { const out = []; let cur = []; for (const p of pts) { if (p.voiced) cur.push(p); else if (cur.length) { out.push(cur); cur = []; } } if (cur.length) out.push(cur); return out; }

  // The stylus trace: o = {w, h, seed, progress 0..1, ticks[], pin, nib, color, restColor, wMin, wMax, rules, blot, accents, gaps}
  window.trace = function (o) {
    const W = o.w, H = o.h, pts = contour(o.seed || 1, o.n || 160, o);
    const p = o.progress == null ? 1 : o.progress;
    const played = pts.filter(t => t.x <= p), rest = pts.filter(t => t.x >= p);
    let s = `<svg aria-hidden="true" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" style="display:block;overflow:visible">`;
    if (o.rules) for (let i = 1; i <= o.rules; i++) { const y = (H / (o.rules + 1)) * i; s += `<line x1="0" x2="${W}" y1="${y}" y2="${y}" stroke="var(--line)" stroke-width="1"/>`; }
    (o.ticks || []).forEach(t => { s += `<line x1="${t * W}" x2="${t * W}" y1="${H * .05}" y2="${H * .95}" stroke="var(--ink-muted)" stroke-width="1.5"/>`; });
    for (const r of runs(rest)) s += `<polyline points="${r.map(t => (t.x * W).toFixed(1) + "," + (t.y * H).toFixed(1)).join(" ")}" fill="none" stroke="${o.restColor || "var(--ink-muted)"}" stroke-width="${o.restW || 1.6}" stroke-dasharray="${o.dash || "1 4"}" stroke-linecap="round"/>`;
    for (const r of runs(played)) s += `<path d="${ribbon(r, W, H, o.wMin || 1.6, o.wMax || 5)}" fill="${o.color || "var(--ink)"}"/>`;
    if (o.pin != null) { const t = pts[Math.round(o.pin * (pts.length - 1))]; const x = o.pin * W, y = t.y * H; s += `<g transform="translate(${x} ${y})"><line y1="${-H * .9}" y2="0" stroke="var(--ink)" stroke-width="1.2"/><rect x="-7" y="${-H * .9 - 7}" width="14" height="14" transform="rotate(45 0 ${-H * .9})" fill="${o.pinFill || "var(--page)"}" stroke="var(--ink)" stroke-width="2.2"/></g>`; }
    if (o.nib !== false && p > 0 && p < 1) { const t = pts[Math.round(p * (pts.length - 1))]; s += `<circle cx="${p * W}" cy="${t.y * H}" r="${o.nibR || 6}" fill="${o.color || "var(--ink)"}"/><circle cx="${p * W}" cy="${t.y * H}" r="${(o.nibR || 6) + 4}" fill="none" stroke="${o.color || "var(--ink)"}" stroke-opacity=".25" stroke-width="2"/>`; }
    if (o.blot && p >= 1) { const t = pts[pts.length - 1]; s += `<circle cx="${W}" cy="${t.y * H}" r="4.5" fill="var(--ink)"/>`; }
    return s + "</svg>";
  };

  // Spectrogram halftone: dot radius follows a synthetic energy field (formant bands drifting over time).
  window.halftone = function (o) {
    const W = o.w, H = o.h, c = o.cell || 7, r = rng(o.seed || 3);
    const f = [0, 1, 2].map(i => ({ base: .18 + i * .24 + r() * .05, sway: .04 + r() * .06, ph: r() * 6 }));
    const syll = 3 + Math.floor(r() * 4);
    let s = `<svg aria-hidden="true" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" style="display:block">`;
    for (let x = c / 2; x < W; x += c) {
      const t = x / W; const env = (.12 + .88 * Math.pow(Math.abs(Math.sin(t * Math.PI * syll + (o.seed || 0))), 1.6)) * (o.fade ? (1 - Math.abs(t - .5) * 1.2) : 1);
      for (let y = c / 2; y < H; y += c) {
        const fy = 1 - y / H; let e = 0;
        f.forEach((b, i) => { const ctr = b.base + Math.sin(t * 9 + b.ph) * b.sway; e += (1 - i * .25) * Math.exp(-((fy - ctr) ** 2) / .006); });
        e = e * env + (r() * .06) * env + (fy < .12 ? .35 * env : 0);
        const rad = Math.min(c * .5, c * .55 * Math.sqrt(Math.max(0, e)));
        if (rad > .45) s += `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${rad.toFixed(2)}"/>`;
      }
    }
    return s.replace("<svg ", `<svg fill="${o.color || "var(--halftone)"}" `) + "</svg>";
  };

  // The stylus P (master geometry in a 100 box; design-src/svg/brand/mark-stylus-p.svg).
  const MARK = "M38 52H62C76 52 82 44 82 35C82 25 75 18 62 18H38V78C38 84 41 87 45 85C50 82 51 72 56 72C61 72 61 84 66 84C70 84 71 77 74 76";
  window.MARK = MARK;
  window.brandMark = function (size, o = {}) {
    const ink = o.ink || "var(--ink)";
    return `<svg aria-hidden="true" width="${size}" height="${size}" viewBox="${o.box || "30 12 60 80"}"><path d="${MARK}" fill="none" stroke="${ink}" stroke-width="${o.sw || 10}" stroke-linecap="round" stroke-linejoin="round" ${o.draw != null ? `pathLength="1" stroke-dasharray="${o.draw} 2"` : ""}/>${o.draw == null || o.draw >= .98 ? `<circle cx="80" cy="75.4" r="7.2" fill="${ink}"/>` : ""}</svg>`;
  };
  // Adaptive launcher icon: kraft drum paper, drum rules, halftone corner, ink stylus P (design-src/svg/brand/launcher-*.svg).
  window.launcherIcon = function (size, o = {}) {
    const mask = o.mask || "squircle", id = "L" + Math.random().toString(36).slice(2, 7);
    const clip = mask === "circle" ? `<circle cx="54" cy="54" r="${o.full ? 54 : 36}"/>` : mask === "none" ? `<rect width="108" height="108"/>`
      : `<path d="M54 18C80 18 90 28 90 54S80 90 54 90 18 80 18 54 28 18 54 18Z"/>`;
    const vb = mask === "none" || o.full ? "0 0 108 108" : "18 18 72 72";
    let dots = ""; if (!o.mono) for (let x = 66; x < 108; x += 4) for (let y = 66; y < 108; y += 4) { const d = Math.hypot(x - 106, y - 106); const r = 1.7 - d / 24; if (r > .3) dots += `<circle cx="${x}" cy="${y}" r="${r.toFixed(2)}"/>`; }
    const rules = o.mono ? "" : [34, 44, 54, 64, 74].map(y => `<line x1="0" y1="${y}" x2="108" y2="${y}"/>`).join("");
    const tile = o.mono ? (o.monoBg || "#c9d7f0") : "#d2c3a3", ink = o.mono ? (o.monoInk || "#0f2a5c") : "#1b1a18";
    return `<svg aria-hidden="true" width="${size}" height="${size}" viewBox="${vb}"><defs><clipPath id="${id}">${clip}</clipPath><radialGradient id="${id}s" cx=".3" cy=".22" r=".75"><stop offset="0" stop-color="#fff" stop-opacity="${o.mono ? 0 : .22}"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>
      <g clip-path="url(#${id})"><rect width="108" height="108" fill="${tile}"/><rect width="108" height="108" fill="url(#${id}s)"/>
      <g stroke="#1b1a18" stroke-opacity=".13" stroke-width=".9">${rules}</g><g fill="#1b1a18" fill-opacity=".24">${dots}</g>
      <g transform="translate(54.5 55) scale(.76) translate(-60 -52)"><path d="${MARK}" fill="none" stroke="${ink}" stroke-width="10" stroke-linecap="round" stroke-linejoin="round"/><circle cx="80" cy="75.4" r="7.2" fill="${ink}"/></g></g></svg>`;
  };

  function statusbar(onMedia) {
    return `<div class="statusbar${onMedia ? " onmedia" : ""}"><span>9:41</span><span class="sys">${icon("wifi", 16, 2.4)}<svg width="24" height="14" viewBox="0 0 24 14" aria-hidden="true"><rect x="1" y="1" width="19" height="12" rx="3" fill="none" stroke="currentColor" stroke-width="1.6"/><rect x="3" y="3" width="13" height="8" rx="1.5" fill="currentColor"/><rect x="21" y="5" width="2" height="4" rx="1" fill="currentColor"/></svg></span></div>`;
  }
  function navbar(active) {
    const items = [["Home", "house"], ["Watch", "square-play"], ["Learn", "graduation-cap"], ["Practice", "target"]];
    return `<nav class="navbar" aria-label="Main">${items.map(([l, i]) => `<div class="item ${l === active ? "on" : ""}"><span class="pill">${icon(i, 22, l === active ? 2.25 : 2)}</span>${l}</div>`).join("")}</nav><div class="gesture"></div>`;
  }
  window.statusbar = statusbar; window.navbar = navbar;

  function hydrate() {
    document.querySelectorAll("[data-statusbar]").forEach(el => { el.outerHTML = statusbar(el.dataset.statusbar === "media"); });
    document.querySelectorAll("[data-navbar]").forEach(el => { el.outerHTML = navbar(el.dataset.navbar); });
    document.querySelectorAll("[data-icon]").forEach(el => { el.outerHTML = icon(el.dataset.icon, +(el.dataset.size || 20), +(el.dataset.sw || 2), el.dataset.cls || ""); });
    document.querySelectorAll("[data-fam]").forEach(el => { el.outerHTML = window.famIcon(el.dataset.fam, +(el.dataset.size || 18), el.dataset.plain == null); });
    document.querySelectorAll("[data-pat]").forEach(el => { el.outerHTML = window.famIcon(el.dataset.pat, 14).replace('class="ico', 'class="ico pat'); });
    document.querySelectorAll("[data-trace]").forEach(el => { el.innerHTML = window.trace(JSON.parse(el.dataset.trace)); });
    document.querySelectorAll("[data-halftone]").forEach(el => { el.innerHTML = window.halftone(JSON.parse(el.dataset.halftone)); });
    document.querySelectorAll("[data-mark]").forEach(el => { el.outerHTML = window.brandMark(+el.dataset.mark, JSON.parse(el.dataset.opts || "{}")); });
    document.querySelectorAll("[data-art]").forEach(el => { const f = (window.ART || {})[el.dataset.art]; el.innerHTML = f ? f(JSON.parse(el.dataset.opts || "{}")) : ""; });
    document.querySelectorAll("[data-launcher]").forEach(el => { el.outerHTML = window.launcherIcon(+el.dataset.launcher, JSON.parse(el.dataset.opts || "{}")); });
    if (window.afterHydrate) window.afterHydrate();
    const ph = document.querySelector(".phone");
    if (ph && q.get("full")) ph.classList.add("tall");
    document.fonts.ready.then(() => { if (ph) document.body.dataset.h = Math.ceil(ph.getBoundingClientRect().height); document.body.dataset.ready = "1"; });
  }
  window.hydrate = hydrate;
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", hydrate); else hydrate();
})();

// The spotlight tether (graft): a hairline from the slip's lower edge to the studied caption word, ending in the
// same diamond that pins the spotlight on the progress trace. Drawn only when nothing lies between the two
// (the word is on the first visible caption line) and the font scale is 1.3 or less; otherwise only the caret.
window.drawTether = function (slipSel, wordSel) {
  const ph = document.querySelector(".phone"), slip = document.querySelector(slipSel), word = document.querySelector(wordSel);
  if (!ph || !slip || !word) return;
  const P = ph.getBoundingClientRect(), A = slip.getBoundingClientRect(), B = word.getBoundingClientRect();
  const x = B.left + B.width / 2 - P.left, y1 = A.bottom - P.top, y2 = B.top - P.top - 3;
  const d = (cx, cy, r, fill) => `<rect x="${cx - r}" y="${cy - r}" width="${2 * r}" height="${2 * r}" transform="rotate(45 ${cx} ${cy})" fill="${fill}" stroke="var(--ink)" stroke-width="1.8"/>`;
  const svg = `<svg class="tether" width="${P.width}" height="${P.height}" aria-hidden="true"><line x1="${x}" y1="${y1}" x2="${x}" y2="${y2 - 6}" stroke="var(--ink)" stroke-width="1.5"/>${d(x, y1, 4.5, "var(--sheet)")}${d(x, y2 - 5, 3.5, "var(--ink)")}</svg>`;
  ph.insertAdjacentHTML("beforeend", svg);
};
