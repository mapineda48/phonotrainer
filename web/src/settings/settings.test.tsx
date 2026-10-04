import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import themeInit from "../../public/theme-init.js?raw";
import { SettingsProvider, useSettings } from "./SettingsProvider";
import {
  DEFAULT_SETTINGS,
  loadSettings,
  parseSettings,
  resolveAttributes,
  SETTINGS_KEY,
  type Settings,
} from "./settings";

describe("parseSettings", () => {
  it("keeps valid fields and replaces only the invalid ones", () => {
    expect(parseSettings({ palette: "cvd", text: "huge", appearance: 3 })).toEqual({
      ...DEFAULT_SETTINGS,
      palette: "cvd",
    });
  });

  it("survives garbage", () => {
    expect(parseSettings(null)).toEqual(DEFAULT_SETTINGS);
    window.localStorage.setItem(SETTINGS_KEY, "{not json");
    expect(loadSettings()).toEqual(DEFAULT_SETTINGS);
  });
});

describe("resolveAttributes", () => {
  it("pattern emphasis follows the palette unless set explicitly", () => {
    expect(resolveAttributes({ ...DEFAULT_SETTINGS, palette: "cvd" }, false)["data-patterns"]).toBe("on");
    expect(resolveAttributes(DEFAULT_SETTINGS, false)["data-patterns"]).toBe("off");
    expect(
      resolveAttributes({ ...DEFAULT_SETTINGS, palette: "cvd", patterns: "off" }, false)["data-patterns"],
    ).toBe("off");
  });

  it("System appearance follows the OS; Light and Dark override it", () => {
    expect(resolveAttributes(DEFAULT_SETTINGS, true)["data-theme"]).toBe("dark");
    expect(resolveAttributes({ ...DEFAULT_SETTINGS, appearance: "light" }, true)["data-theme"]).toBe("light");
    expect(resolveAttributes({ ...DEFAULT_SETTINGS, appearance: "dark" }, false)["data-theme"]).toBe("dark");
  });
});

describe("public/theme-init.js (runs before the first paint)", () => {
  const cases: [Partial<Settings> | string, boolean][] = [
    [{}, false],
    [{}, true],
    [{ appearance: "dark", palette: "cvd" }, false],
    [{ appearance: "light", palette: "cvd", patterns: "off", text: "larger", motion: "reduce" }, true],
    [{ palette: "standard", patterns: "on", text: "large" }, false],
    ["{broken", true],
  ];

  it.each(cases)("sets the same attributes as resolveAttributes for %j (OS dark: %s)", (stored, prefersDark) => {
    window.localStorage.setItem(SETTINGS_KEY, typeof stored === "string" ? stored : JSON.stringify(stored));
    const original = window.matchMedia;
    window.matchMedia = ((query: string) => ({ matches: prefersDark, media: query })) as typeof window.matchMedia;
    try {
      new Function(themeInit)();
    } finally {
      window.matchMedia = original;
    }
    const expected = resolveAttributes(loadSettings(), prefersDark);
    for (const [name, value] of Object.entries(expected)) {
      expect(document.documentElement.getAttribute(name), name).toBe(value);
    }
  });
});

function Probe() {
  const { settings, update, patterns } = useSettings();
  return (
    <div>
      <p>palette: {settings.palette}</p>
      <p>patterns: {String(patterns)}</p>
      <button onClick={() => update({ palette: "cvd" })}>cvd</button>
      <button onClick={() => update({ text: "larger" })}>larger</button>
    </div>
  );
}

describe("SettingsProvider", () => {
  it("applies a change to <html> at once and remembers it", async () => {
    render(
      <SettingsProvider>
        <Probe />
      </SettingsProvider>,
    );
    expect(document.documentElement.getAttribute("data-palette")).toBe("standard");

    await userEvent.click(screen.getByRole("button", { name: "cvd" }));
    expect(document.documentElement.getAttribute("data-palette")).toBe("cvd");
    expect(document.documentElement.getAttribute("data-patterns")).toBe("on");
    expect(screen.getByText("patterns: true")).toBeInTheDocument();
    expect(JSON.parse(window.localStorage.getItem(SETTINGS_KEY)!)).toMatchObject({ v: 1, palette: "cvd" });

    await userEvent.click(screen.getByRole("button", { name: "larger" }));
    expect(document.documentElement.getAttribute("data-text")).toBe("larger");
  });

  it("starts from what was saved", () => {
    window.localStorage.setItem(SETTINGS_KEY, JSON.stringify({ v: 1, palette: "cvd", appearance: "dark" }));
    act(() => {
      render(
        <SettingsProvider>
          <Probe />
        </SettingsProvider>,
      );
    });
    expect(screen.getByText("palette: cvd")).toBeInTheDocument();
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
  });
});
