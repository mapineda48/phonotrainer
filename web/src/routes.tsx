/** The route table. Each page comes from its feature's src/features/<feature>/index.tsx,
 *  whose export names and props are the contract. Route shapes live in paths.ts (ROUTES
 *  + the paths.* builders).
 *
 *  Every page is code-split: its JavaScript is fetched the first time its route is
 *  visited, so the first screen only waits for the shell. The shell, the settings and the
 *  theme are in the main bundle and paint at once; a page that is still loading shows
 *  <PageLoading> inside <main>. The two Library pages are imported from their own modules
 *  (the ones library/index.tsx re-exports) so that each gets its own chunk. */

import { CloudOff, Compass, LoaderCircle } from "lucide-react";
import { lazy, Suspense, useEffect, useState, type ComponentType, type ReactNode } from "react";
import { Route, Switch, useSearch } from "wouter";

import { paths, ROUTES } from "./paths";
import { useDocumentTitle } from "./shell/useDocumentTitle";
import { Button, EmptyState, LinkButton } from "./ui";

/** React.lazy for a named export. If the chunk cannot be fetched (the interface was
 *  rebuilt or the server stopped since this tab opened), the page becomes
 *  <PageLoadFailed> instead of blanking the whole app. */
function lazyPage<M extends Record<K, ComponentType<any>>, K extends keyof M>(load: () => Promise<M>, name: K) {
  return lazy(() =>
    load().then(
      (module) => ({ default: module[name] }),
      (err: unknown) => {
        console.error(`Could not load the code of ${String(name)}:`, err);
        return { default: PageLoadFailed as unknown as M[K] };
      },
    ),
  );
}

const LibraryPage = lazyPage(() => import("./features/library/LibraryPage"), "LibraryPage");
const NewAnalysisPage = lazyPage(() => import("./features/library/NewAnalysisPage"), "NewAnalysisPage");
const AnalysisPage = lazyPage(() => import("./features/analysis"), "AnalysisPage");
const LearnHomePage = lazyPage(() => import("./features/learn"), "LearnHomePage");
const IpaChartPage = lazyPage(() => import("./features/learn"), "IpaChartPage");
const PhenomenonPage = lazyPage(() => import("./features/learn"), "PhenomenonPage");
const PracticePage = lazyPage(() => import("./features/practice"), "PracticePage");
const InsightsPage = lazyPage(() => import("./features/insights"), "InsightsPage");
const SettingsPage = lazyPage(() => import("./shell/SettingsPage"), "SettingsPage");

/** How long a page may take to arrive before the loading message shows. From the local
 *  server it is usually much less, and then nothing flashes and nothing is announced. */
const LOADING_DELAY_MS = 300;

/** Stands in for a page whose code is on its way. The status region is in place from the
 *  start, empty, so screen readers announce the message (politely) when it appears; it
 *  sits where the pages' own "Loading…" lines do, so nothing jumps; the spinner only
 *  turns when motion is allowed. */
export function PageLoading() {
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    const timer = window.setTimeout(() => setVisible(true), LOADING_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, []);
  return (
    <p role="status" className="flex min-h-32 items-center justify-center gap-2 p-12 text-ink-2">
      {visible && (
        <>
          <LoaderCircle size={20} aria-hidden="true" className="motion-ok:animate-spin" />
          Loading this page…
        </>
      )}
    </p>
  );
}

function PageLoadFailed() {
  return (
    <EmptyState
      icon={CloudOff}
      title="This page could not be loaded"
      level={1}
      className="mt-16"
      actions={<Button onPress={() => window.location.reload()}>Reload</Button>}
    >
      The interface may have been updated, or the server stopped, since this tab was opened.
    </EmptyState>
  );
}

/** Where a code-split page renders, with <PageLoading> until it arrives. */
function Deferred({ children }: { children: ReactNode }) {
  return <Suspense fallback={<PageLoading />}>{children}</Suspense>;
}

function decode(value: string | undefined): string {
  if (value === undefined) return "";
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function AnalysisRoute({ id, segment, index }: { id: string; segment?: string; index?: string }) {
  const from = new URLSearchParams(useSearch()).get("from");
  const s = segment === undefined ? NaN : Number(segment);
  const i = index === undefined ? NaN : Number(index);
  const selection = Number.isInteger(s) && Number.isInteger(i) ? { segment: s, index: i } : null;
  return (
    <Deferred>
      <AnalysisPage jobId={decode(id)} selection={selection} from={from} />
    </Deferred>
  );
}

/** Sets the tab title at once, before the page's code has arrived. */
function Titled({ title, children }: { title: string; children: ReactNode }) {
  useDocumentTitle(title);
  return <Deferred>{children}</Deferred>;
}

export function NotFoundPage() {
  useDocumentTitle("Not found");
  return (
    <EmptyState
      icon={Compass}
      title="This page does not exist"
      level={1}
      className="mt-16"
      actions={<LinkButton href={paths.library()}>Go to your analyses</LinkButton>}
    >
      The link may be old, or the analysis may have been deleted.
    </EmptyState>
  );
}

export function AppRoutes() {
  return (
    <Switch>
      <Route path={ROUTES.library}>
        <Titled title="Library">
          <LibraryPage />
        </Titled>
      </Route>
      <Route path={ROUTES.newAnalysis}>
        <Titled title="New analysis">
          <NewAnalysisPage />
        </Titled>
      </Route>
      <Route path={ROUTES.word}>
        {(params) => <AnalysisRoute id={params.id} segment={params.segment} index={params.index} />}
      </Route>
      <Route path={ROUTES.analysis}>{(params) => <AnalysisRoute id={params.id} />}</Route>
      <Route path={ROUTES.ipa}>
        <Titled title="IPA chart">
          <IpaChartPage />
        </Titled>
      </Route>
      <Route path={ROUTES.learn}>
        <Titled title="Learn">
          <LearnHomePage />
        </Titled>
      </Route>
      <Route path={ROUTES.phenomenon}>
        {(params) => (
          <Deferred>
            <PhenomenonPage name={decode(params.phenomenon)} />
          </Deferred>
        )}
      </Route>
      <Route path={ROUTES.practice}>
        <Titled title="Practice">
          <PracticePage />
        </Titled>
      </Route>
      <Route path={ROUTES.insights}>
        <Titled title="Insights">
          <InsightsPage />
        </Titled>
      </Route>
      <Route path={ROUTES.settings}>
        <Titled title="Settings">
          <SettingsPage />
        </Titled>
      </Route>
      <Route>
        <NotFoundPage />
      </Route>
    </Switch>
  );
}
