/** Render helpers for page and component tests.
 *
 *    const { history, navigate } = renderPage(<LibraryPage />, { path: "/", jobs: [job] });
 *
 *  Wraps the UI in everything a page can rely on: display settings, a router with an
 *  in-memory location (the URL never leaks between tests), React Aria's router bridge,
 *  the phenomenon reference, the jobs channel (fake socket) and, optionally, the player. */

import { render, type RenderOptions } from "@testing-library/react";
import type { ReactElement, ReactNode } from "react";
import { Router } from "wouter";
import { memoryLocation } from "wouter/memory-location";

import { AriaRouterBridge } from "../App";
import { JobsProvider } from "../jobs/JobsProvider";
import { PlayerContextProvider, type PlayerApi } from "../player/PlayerProvider";
import { ReferenceProvider } from "../reference";
import { SettingsProvider } from "../settings/SettingsProvider";
import type { Settings } from "../settings/settings";
import type { Job, Reference } from "../types";
import { fakeJobsChannel, reference as basicReference } from "./fixtures";
import fullReferenceJson from "./reference.full.json";

/** The real /api/reference of the current backend (every label, family, practice
 *  advice, metric reference and engine note), exported from the server. Use it when a
 *  test needs the complete taxonomy; `reference` from fixtures.tsx is a small subset. */
export const fullReference = fullReferenceJson as unknown as Reference;

export interface RenderPageOptions extends Omit<RenderOptions, "wrapper"> {
  /** Initial URL, with query if needed ("/insights?phenomenon=flapping"). Default "/". */
  path?: string;
  reference?: Reference;
  /** Jobs delivered by the fake WebSocket snapshot. */
  jobs?: Job[];
  /** Start from these display settings instead of the defaults. */
  settings?: Partial<Settings>;
  /** Provide a (fake) player, for components that need PlayerContext. */
  player?: PlayerApi;
}

export function renderPage(ui: ReactElement, options: RenderPageOptions = {}) {
  const { path = "/", reference = basicReference, jobs = [], settings, player, ...rest } = options;
  const location = memoryLocation({ path, record: true });
  const jobsTools = fakeJobsChannel(jobs);

  const Wrapper = ({ children }: { children: ReactNode }) => {
    const inner = player ? <PlayerContextProvider value={player}>{children}</PlayerContextProvider> : children;
    return (
      <SettingsProvider initial={settings}>
        <Router hook={location.hook} searchHook={location.searchHook}>
          <AriaRouterBridge>
            <ReferenceProvider value={reference}>
              <JobsProvider channel={jobsTools.channel}>{inner}</JobsProvider>
            </ReferenceProvider>
          </AriaRouterBridge>
        </Router>
      </SettingsProvider>
    );
  };

  const result = render(ui, { wrapper: Wrapper, ...rest });
  return {
    ...result,
    /** Every URL visited, in order (the current one last). */
    history: location.history ?? [],
    /** Navigate programmatically, as a link would. */
    navigate: location.navigate,
    /** The fake jobs channel: push events with `jobs.socket.push(...)` after the page subscribed. */
    jobs: jobsTools,
  };
}
