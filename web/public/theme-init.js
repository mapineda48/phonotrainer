/* PhonoTrainer — apply the saved display settings before the first paint, so the page
   never flashes the wrong theme. Loaded synchronously from <head> as an external file
   (no inline script). Mirrors resolveAttributes() in src/settings/settings.ts; the test
   src/settings/settings.test.ts runs both on the same inputs. */
(function () {
  var s = {};
  try {
    s = JSON.parse(window.localStorage.getItem("phonotrainer:settings") || "{}") || {};
  } catch (e) {
    s = {};
  }
  var prefersDark = false;
  try {
    prefersDark = !!(window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);
  } catch (e) {
    prefersDark = false;
  }
  var appearance = s.appearance === "light" || s.appearance === "dark" ? s.appearance : "system";
  var dark = appearance === "dark" || (appearance === "system" && prefersDark);
  var palette = s.palette === "cvd" ? "cvd" : "standard";
  var patterns = s.patterns === "on" || s.patterns === "off" ? s.patterns : palette === "cvd" ? "on" : "off";
  var text = s.text === "large" || s.text === "larger" ? s.text : "default";
  var motion = s.motion === "reduce" ? "reduce" : "system";
  var root = document.documentElement;
  root.setAttribute("data-theme", dark ? "dark" : "light");
  root.setAttribute("data-palette", palette);
  root.setAttribute("data-patterns", patterns);
  root.setAttribute("data-text", text);
  root.setAttribute("data-motion", motion);
})();
