import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vitest/config";

const LICENSES_FILE = "third-party-licenses.txt";

// The minifier strips the `@license` banners of the libraries bundled into the
// interface, and those notices have to travel with the copies, so we put them
// back on top of the already-generated bundle. It runs as a plugin rather than
// as `rollupOptions.output.banner` because Vite 8's bundler minifies that away
// all the same. Every chunk gets it, including the separate one three.js lands
// in, and so does the stylesheet (Tailwind's preflight and driver.js's styles are
// copied into it). The full license texts go next to the bundle, in
// third-party-licenses.txt (see licenseTexts below).
function licenseBanner(): Plugin {
  const js =
    "/*! PhonoTrainer — GPL-3.0-or-later. Includes React, react-dom, scheduler and" +
    " use-sync-external-store: Copyright (c) Meta Platforms, Inc. and affiliates, MIT license;" +
    " three.js: Copyright © 2010-2026 three.js authors, MIT license;" +
    " React Aria Components, react-aria, react-stately, @internationalized/string and" +
    " @internationalized/number: Copyright 2019 Adobe, Apache License 2.0;" +
    " lucide-react: Copyright (c) 2026 Lucide Icons and Contributors, ISC license;" +
    " clsx and regexparam: Copyright (c) Luke Edwards, MIT license;" +
    " tailwind-merge: Copyright (c) 2021 Dany Castillo, MIT license;" +
    " tailwind-variants: Copyright (c) 2020 Tailwind Variants, MIT license;" +
    " driver.js: Copyright (c) Kamran Ahmed, MIT license;" +
    " wouter: Alexey Taktarov, The Unlicense." +
    ` Full license texts: ${LICENSES_FILE}; attribution: THIRD-PARTY-NOTICES.md. */\n`;
  const css =
    "/*! PhonoTrainer — GPL-3.0-or-later. Includes the Tailwind CSS preflight" +
    " (Copyright (c) Tailwind Labs, Inc., MIT license) and driver.js styles" +
    " (Copyright (c) Kamran Ahmed, MIT license). Fonts, shipped as separate files under the" +
    " SIL Open Font License 1.1: Atkinson Hyperlegible Next (Copyright 2020-2024 The Atkinson" +
    " Hyperlegible Next Project Authors) and Charis 7.000 (Copyright (c) 1997-2025 SIL Global," +
    ` Reserved Font Names "Charis" and "SIL"). Full license texts: ${LICENSES_FILE}. */\n`;
  return {
    name: "phonotrainer:license-banner",
    apply: "build",
    generateBundle(_options, bundle) {
      for (const file of Object.values(bundle)) {
        if (file.type === "chunk") file.code = js + file.code;
        else if (file.fileName.endsWith(".css") && typeof file.source === "string") file.source = css + file.source;
      }
    },
  };
}

/** The npm package a bundled module comes from ("react-aria", "@fontsource-variable/…"),
 *  with its folder; null for our own sources. */
function packageOf(id: string): { name: string; dir: string } | null {
  const clean = id.replace(/\0/g, "").split("?")[0];
  const at = clean.lastIndexOf("/node_modules/");
  if (at < 0) return null;
  const parts = clean.slice(at + "/node_modules/".length).split("/");
  const name = parts[0].startsWith("@") ? `${parts[0]}/${parts[1]}` : parts[0];
  return { name, dir: `${clean.slice(0, at)}/node_modules/${name}` };
}

// Apache-2.0 and the OFL ask for the license TEXT to travel with every copy, not only
// a notice, so the build writes dist/third-party-licenses.txt: one section per npm
// package that actually ended up in a chunk (found from the chunks' module ids, so it
// never drifts from package.json), plus the vendored Charis font.
function licenseTexts(): Plugin {
  return {
    name: "phonotrainer:license-texts",
    apply: "build",
    generateBundle(_options, bundle) {
      const packages = new Map<string, string>();
      for (const file of Object.values(bundle)) {
        if (file.type !== "chunk") continue;
        for (const id of file.moduleIds) {
          const pkg = packageOf(id);
          if (pkg) packages.set(pkg.name, pkg.dir);
        }
      }
      const sections = [...packages].sort(([a], [b]) => a.localeCompare(b)).map(([name, dir]) => {
        const meta = JSON.parse(readFileSync(path.join(dir, "package.json"), "utf8"));
        const license = typeof meta.license === "string" ? meta.license : JSON.stringify(meta.license);
        const file = readdirSync(dir).find((entry) => /^(licen[cs]e|copying)(\.(md|txt))?$/i.test(entry));
        const text = file
          ? readFileSync(path.join(dir, file), "utf8").trim()
          : `${license} (declared in its package.json; the package ships no license file).`;
        return `${name} ${meta.version} — ${license}\n\n${text}`;
      });
      // Not modules of any chunk, but copied into the bundle all the same: Tailwind's
      // preflight is emitted into the stylesheet, and Charis ships as font files.
      const tailwind = fileURLToPath(new URL("./node_modules/tailwindcss/LICENSE", import.meta.url));
      if (existsSync(tailwind)) {
        sections.push(`tailwindcss (its preflight, in the stylesheet) — MIT\n\n${readFileSync(tailwind, "utf8").trim()}`);
      }
      const charis = fileURLToPath(new URL("./src/assets/fonts/charis/OFL.txt", import.meta.url));
      if (existsSync(charis)) {
        sections.push(`Charis 7.000 (SIL Global; the Charis-*.woff2 files, unmodified) — OFL-1.1\n\n${readFileSync(charis, "utf8").trim()}`);
      }
      const rule = `\n\n${"=".repeat(78)}\n\n`;
      this.emitFile({
        type: "asset",
        fileName: LICENSES_FILE,
        source:
          "PhonoTrainer's web interface is GPL-3.0-or-later. It bundles the third-party\n" +
          "software and fonts below, each under its own license (attribution and the reasons\n" +
          "they are compatible: THIRD-PARTY-NOTICES.md in the source repository)." +
          rule +
          sections.join(rule) +
          "\n",
      });
    },
  };
}

// The backend (phonotrainer ui) serves dist/ in production; under `npm run dev`
// we proxy /api to the FastAPI server so we get HMR. /ws is the WebSocket that
// carries analysis state: it is proxied too.
export default defineConfig({
  plugins: [react(), tailwindcss(), licenseBanner(), licenseTexts()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://127.0.0.1:8000",
      "/ws": { target: "http://127.0.0.1:8000", ws: true },
    },
  },
  build: { outDir: "dist", emptyOutDir: true },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    // CSS is stubbed out in tests, except what theme tests read raw: the tokens
    // (palette.test.ts) and the entry stylesheet (focus.test.ts).
    css: { include: [/src\/theme\/tokens\.css/, /src\/theme\/index\.css/] },
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
