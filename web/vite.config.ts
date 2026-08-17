import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vitest/config";

// The minifier strips the `@license` banners of React, react-dom and scheduler
// (MIT, by Meta), and that notice has to travel with the copies, so we put it
// back on top of the already-generated bundle. It runs as a plugin rather than
// as `rollupOptions.output.banner` because Vite 8's bundler minifies that away
// all the same.
function licenseBanner(): Plugin {
  const notice =
    "/*! PhonoTrainer — GPL-3.0-or-later. Includes React, react-dom and scheduler:" +
    " Copyright (c) Meta Platforms, Inc. and affiliates, MIT license" +
    " (https://github.com/facebook/react/blob/main/LICENSE)." +
    " Full attribution: THIRD-PARTY-NOTICES.md. */\n";
  return {
    name: "phonotrainer:license-banner",
    apply: "build",
    generateBundle(_options, bundle) {
      for (const chunk of Object.values(bundle)) {
        if (chunk.type === "chunk") chunk.code = notice + chunk.code;
      }
    },
  };
}

// The backend (phonotrainer ui) serves dist/ in production; under `npm run dev`
// we proxy /api to the FastAPI server so we get HMR. /ws is the WebSocket that
// carries analysis state: it is proxied too.
export default defineConfig({
  plugins: [react(), licenseBanner()],
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
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
