import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// El backend (phonotrainer ui) sirve dist/ en producción; en `npm run dev`
// hacemos proxy de /api al servidor de FastAPI para tener HMR.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { "/api": "http://127.0.0.1:8000" },
  },
  build: { outDir: "dist", emptyOutDir: true },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
