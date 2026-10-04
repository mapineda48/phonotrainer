import "@testing-library/jest-dom/vitest";

import { cleanup, configure } from "@testing-library/react";
import { afterEach, vi } from "vitest";

// findBy*/waitFor give up after 1 s by default. The first full render of an
// analysis takes ~200 ms here, but well over a second on a loaded CPU (models
// loading, other suites running), which failed tests that were correct. Waiting
// longer costs nothing when it passes: findBy resolves as soon as the element
// appears. The test timeout is raised to stay above it.
configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20000 });

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  // The app routes with the real browser location (wouter) and writes display settings
  // to <html> and localStorage: start every test from "/" with a clean slate.
  window.history.replaceState(null, "", "/");
  for (const name of ["data-theme", "data-palette", "data-patterns", "data-text", "data-motion"]) {
    document.documentElement.removeAttribute(name);
  }
  try {
    window.localStorage.clear();
  } catch {
    /* no storage in this environment */
  }
});

// jsdom has no matchMedia; the settings read prefers-color-scheme through it.
if (typeof window.matchMedia !== "function") {
  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    writable: true,
    value: (query: string): MediaQueryList =>
      ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => false,
      }) as MediaQueryList,
  });
}

// jsdom does not implement these pieces, which the app does use.
if (!globalThis.ResizeObserver) {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
}

// The waveform canvas is not drawn under jsdom; return null without any noise.
Object.defineProperty(window.HTMLCanvasElement.prototype, "getContext", {
  configurable: true,
  value: () => null,
});

Object.defineProperty(window.HTMLMediaElement.prototype, "play", {
  configurable: true,
  value: () => Promise.resolve(),
});
Object.defineProperty(window.HTMLMediaElement.prototype, "pause", {
  configurable: true,
  value: () => undefined,
});
