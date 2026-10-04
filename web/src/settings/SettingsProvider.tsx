/** Settings context: read anywhere with useSettings(), changed with update().
 *
 *  Every change is saved and re-applied to <html> at once, and with Appearance =
 *  System the theme follows the OS live (prefers-color-scheme). */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

import {
  applyAttributes,
  DARK_QUERY,
  loadSettings,
  resolveAttributes,
  saveSettings,
  systemPrefersDark,
  type Settings,
} from "./settings";

export interface SettingsApi {
  settings: Settings;
  update: (patch: Partial<Settings>) => void;
  /** What Appearance resolves to right now. */
  theme: "light" | "dark";
  /** Pattern emphasis after resolving "auto". */
  patterns: boolean;
}

const SettingsContext = createContext<SettingsApi | null>(null);

interface Props {
  children: ReactNode;
  /** Tests start from a known state instead of localStorage. */
  initial?: Partial<Settings>;
  /** Where the attributes go (default: <html>). */
  root?: HTMLElement;
}

export function SettingsProvider({ children, initial, root }: Props) {
  const [settings, setSettings] = useState<Settings>(() => ({ ...loadSettings(), ...initial }));
  const [prefersDark, setPrefersDark] = useState(systemPrefersDark);

  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const query = window.matchMedia(DARK_QUERY);
    const onChange = () => setPrefersDark(query.matches);
    query.addEventListener?.("change", onChange);
    return () => query.removeEventListener?.("change", onChange);
  }, []);

  const attributes = useMemo(() => resolveAttributes(settings, prefersDark), [settings, prefersDark]);

  useLayoutEffect(() => {
    applyAttributes(root ?? document.documentElement, attributes);
  }, [attributes, root]);

  const update = useCallback((patch: Partial<Settings>) => {
    setSettings((current) => {
      const next = { ...current, ...patch };
      saveSettings(next);
      return next;
    });
  }, []);

  const value = useMemo<SettingsApi>(
    () => ({
      settings,
      update,
      theme: attributes["data-theme"] === "dark" ? "dark" : "light",
      patterns: attributes["data-patterns"] === "on",
    }),
    [settings, update, attributes],
  );

  return <SettingsContext.Provider value={value}>{children}</SettingsContext.Provider>;
}

export function useSettings(): SettingsApi {
  const api = useContext(SettingsContext);
  if (!api) throw new Error("useSettings() requires a <SettingsProvider>");
  return api;
}
