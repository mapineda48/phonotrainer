// Phonautograph, change request CR-01: the reach strip of the removal dialogs and the mini route of a deletion.
// Reference drawings for android/docs/design.md 9.26. Both are decorative (hidden from TalkBack): the words next to
// them say the same thing.
(function () {
  const I = (n, s = 22, sw = 2) => window.icon(n, s, sw);
  const node = (glyph, cls, badge) =>
    `<span class="pnode ${cls || ""}">${I(glyph)}${badge ? `<span class="bdg">${I(badge, 12, 2.6)}</span>` : ""}</span>`;

  // kind: "computer" (Delete from your computer?), "list" (the imported variant), "phone" (Remove from this phone?)
  window.reach = function (kind) {
    if (kind === "phone") {
      return `<div class="reach two" aria-hidden="true"><span class="wire dotted" style="left:42px;width:124px"></span>
        <div class="col">${node("smartphone", "here", "trash-2")}<b>This phone</b></div>
        <div class="col">${node("monitor", "", "check")}Your computer</div></div>`;
    }
    const hub = kind === "list" ? "Your computer's list" : "Your computer";
    return `<div class="reach four" aria-hidden="true"><span class="wire" style="left:12.5%;right:12.5%"></span>
      <div class="col">${node("monitor", "hub", "trash-2")}<b>${hub}</b></div>
      <div class="col">${node("smartphone", "here", "trash-2")}This phone</div>
      <div class="col">${node("smartphone", "", "trash-2")}Other phones</div>
      <div class="col">${node("app-window", "", "trash-2")}Browser</div></div>`;
  };

  // state: "waiting" (parked on this phone: dotted, nothing travels) or "sending" (solid, the nib on its way)
  window.mroute = function (state, w = 190) {
    const sending = state === "sending";
    const tr = window.trace({ w, h: 22, seed: 7, progress: sending ? 0.55 : 0, nib: sending, accents: 3, dash: "1 5", restW: 2, wMin: 2, wMax: 3.6, nibR: 4.5 });
    return `<div class="mroute" aria-hidden="true">
      <div class="n ${sending ? "" : "here"}">${node("smartphone", sending ? "" : "here", sending ? "" : "trash-2")}This phone</div>
      <div class="tr">${tr}</div>
      <div class="n">${node("monitor", "", sending ? "trash-2" : "")}Your computer</div></div>`;
  };

  const prev = window.afterHydrate;
  window.afterHydrate = function () {
    document.querySelectorAll("[data-reach]").forEach(el => { el.outerHTML = window.reach(el.dataset.reach); });
    document.querySelectorAll("[data-mroute]").forEach(el => { el.outerHTML = window.mroute(el.dataset.mroute, +(el.dataset.w || 190)); });
    if (prev) prev();
  };
})();
