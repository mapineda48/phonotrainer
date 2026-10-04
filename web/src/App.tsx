/** App root: display settings, routing, the phenomenon taxonomy from the backend, and
 *  the shell around the routed page. */

import { useEffect, useState, type ReactNode } from "react";
import { RouterProvider } from "react-aria-components";
import { Router, useLocation } from "wouter";

import { api, REQUIRED_API_VERSION } from "./api";
import { useJobs } from "./hooks/useJobs";
import { ReferenceProvider } from "./reference";
import { AppRoutes } from "./routes";
import { SettingsProvider } from "./settings/SettingsProvider";
import { AppShell } from "./shell/AppShell";
import type { Reference } from "./types";

/** Lets React Aria links and menu items navigate client-side through wouter. */
export function AriaRouterBridge({ children }: { children: ReactNode }) {
  const [, navigate] = useLocation();
  return (
    <RouterProvider navigate={(href) => navigate(href)} useHref={(href) => href}>
      {children}
    </RouterProvider>
  );
}

function Message({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto flex min-h-dvh max-w-xl flex-col items-center justify-center gap-2 px-6 text-center text-base text-ink">
      {children}
    </div>
  );
}

function Workbench() {
  // Connect the jobs WebSocket at startup, not when the first page asks for it: the
  // analyses (and their progress) are then ready on whichever page opens.
  useJobs();
  const [reference, setReference] = useState<Reference | null>(null);
  const [referenceError, setReferenceError] = useState<string | null>(null);

  useEffect(() => {
    api
      .reference()
      .then(setReference)
      .catch((err: unknown) => setReferenceError(err instanceof Error ? err.message : String(err)));
  }, []);

  if (referenceError) {
    return (
      <Message>
        <p className="font-semibold">Could not reach the backend: {referenceError}</p>
        <p className="text-sm text-ink-2">Start the server with “phonotrainer ui”.</p>
      </Message>
    );
  }
  if (!reference) return <Message>Loading…</Message>;
  // The UI is served from disk and is always up to date; the process answering may be
  // an old one left running on that port. Without this warning the UI would request
  // routes that server does not have and the learner would only see errors.
  if ((reference.api_version ?? 0) < REQUIRED_API_VERSION) {
    return (
      <Message>
        <p className="font-semibold">The server answering on this port is older than this interface.</p>
        <p className="text-sm text-ink-2">
          An earlier <code>phonotrainer ui</code> was probably left running. Stop it (Ctrl-C) and start it
          again, or use the new one's port.
        </p>
      </Message>
    );
  }

  return (
    <ReferenceProvider value={reference}>
      <AppShell>
        <AppRoutes />
      </AppShell>
    </ReferenceProvider>
  );
}

export default function App() {
  return (
    <SettingsProvider>
      <Router>
        <AriaRouterBridge>
          <Workbench />
        </AriaRouterBridge>
      </Router>
    </SettingsProvider>
  );
}
