import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// El backend (phonotrainer ui) sirve dist/ en producción; en `npm run dev`
// hacemos proxy de /api al servidor de FastAPI para tener HMR. /ws es el
// WebSocket del estado de los análisis: también va por proxy.
export default defineConfig({
  plugins: [react()],
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
