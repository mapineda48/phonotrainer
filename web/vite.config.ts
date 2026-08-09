import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vitest/config";

// El minificador borra los banners `@license` de React, react-dom y scheduler
// (MIT, de Meta) y esa nota tiene que viajar con las copias, así que la
// reponemos sobre el bundle ya generado. Va como plugin y no como
// `rollupOptions.output.banner` porque el bundler de Vite 8 lo minifica igual.
function licenseBanner(): Plugin {
  const notice =
    "/*! PhonoTrainer — GPL-3.0-or-later. Incluye React, react-dom y scheduler:" +
    " Copyright (c) Meta Platforms, Inc. y afiliadas, licencia MIT" +
    " (https://github.com/facebook/react/blob/main/LICENSE)." +
    " Atribución completa: THIRD-PARTY-NOTICES.md. */\n";
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

// El backend (phonotrainer ui) sirve dist/ en producción; en `npm run dev`
// hacemos proxy de /api al servidor de FastAPI para tener HMR. /ws es el
// WebSocket del estado de los análisis: también va por proxy.
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
