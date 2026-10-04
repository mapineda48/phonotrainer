/** The route table. Each page comes from its feature's src/features/<feature>/index.tsx,
 *  whose export names and props are the contract. Route shapes live in paths.ts (ROUTES
 *  + the paths.* builders). */

import { Compass } from "lucide-react";
import { Route, Switch, useSearch } from "wouter";

import { AnalysisPage } from "./features/analysis";
import { InsightsPage } from "./features/insights";
import { IpaChartPage, LearnHomePage, PhenomenonPage } from "./features/learn";
import { LibraryPage, NewAnalysisPage } from "./features/library";
import { PracticePage } from "./features/practice";
import { paths, ROUTES } from "./paths";
import { SettingsPage } from "./shell/SettingsPage";
import { useDocumentTitle } from "./shell/useDocumentTitle";
import { EmptyState, LinkButton } from "./ui";

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
  return <AnalysisPage jobId={decode(id)} selection={selection} from={from} />;
}

function Titled({ title, children }: { title: string; children: React.ReactNode }) {
  useDocumentTitle(title);
  return <>{children}</>;
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
        {(params) => <PhenomenonPage name={decode(params.phenomenon)} />}
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
        <SettingsPage />
      </Route>
      <Route>
        <NotFoundPage />
      </Route>
    </Switch>
  );
}
