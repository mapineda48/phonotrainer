/** Library: every analysis, live. Status is an icon AND a word; running analyses show
 *  which pipeline stage they are in. Opening a row studies it; the ⋯ menu holds the
 *  rest (report, cancel, retry, delete — deletion always asks first). */

import {
  CircleStop,
  Clapperboard,
  ExternalLink,
  FolderInput,
  MoreHorizontal,
  Play,
  Plus,
  RotateCcw,
  Trash2,
} from "lucide-react";
import { useMemo, useState } from "react";
import { GridList, GridListItem, MenuTrigger } from "react-aria-components";
import { useLocation } from "wouter";

import { api } from "../../api";
import { tourAttr, TOUR } from "../../didactic/tour-ids";
import { useJobs } from "../../hooks/useJobs";
import { useJobsChannel } from "../../jobs/JobsProvider";
import { fmtDuration } from "../../lib/format";
import { paths } from "../../paths";
import type { Job } from "../../types";
import {
  AppDialog,
  Button,
  Card,
  Chip,
  cn,
  ConfirmDialog,
  EmptyState,
  IconButton,
  LinkButton,
  Menu,
  MenuItem,
  MenuSeparator,
  Notice,
  PageHeader,
  Popover,
  ProgressBar,
  SearchField,
  Select,
} from "../../ui";
import { startTour } from "../tour";
import {
  engineLabel,
  isActive,
  jobEngine,
  lastJobId,
  relativeTime,
  rememberJob,
  rulesVersion,
  STATUS,
} from "./jobInfo";
import { PathBrowser } from "./PathBrowser";
import { stageSummary } from "../../jobs/stages";

type Sort = "newest" | "oldest" | "name";

const SORTS: { id: Sort; label: string }[] = [
  { id: "newest", label: "Newest first" },
  { id: "oldest", label: "Oldest first" },
  { id: "name", label: "Name (A–Z)" },
];

const message = (err: unknown) => (err instanceof Error ? err.message : String(err));
const analyses = (count: number) => `${count} ${count === 1 ? "analysis" : "analyses"}`;

export function LibraryPage() {
  const { jobs, loaded, error: channelError } = useJobs();
  const channel = useJobsChannel();
  const [, navigate] = useLocation();
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<Sort>("newest");
  const [engine, setEngine] = useState<string>("all");
  const [deleting, setDeleting] = useState<Job | null>(null);
  const [importing, setImporting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const engines = useMemo(
    () => [...new Set(jobs.map(jobEngine).filter((e): e is string => Boolean(e)))].sort(),
    [jobs],
  );
  const newestRules = useMemo(
    () => Math.max(0, ...jobs.map((job) => rulesVersion(job) ?? 0)),
    [jobs],
  );

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const list = jobs.filter(
      (job) =>
        (!needle || job.source.toLowerCase().includes(needle)) &&
        (engine === "all" || jobEngine(job) === engine),
    );
    return [...list].sort((a, b) => {
      if (sort === "name") return a.source.localeCompare(b.source);
      const order = a.created.localeCompare(b.created);
      return sort === "newest" ? -order : order;
    });
  }, [jobs, query, sort, engine]);

  const last = jobs.find((job) => job.id === lastJobId() && job.status === "done") ?? null;

  const open = (id: string) => {
    rememberJob(id);
    navigate(paths.analysis(id));
  };

  const act = async (action: () => Promise<unknown>) => {
    setActionError(null);
    try {
      await action();
    } catch (err) {
      setActionError(message(err));
    }
  };

  const retry = (job: Job) =>
    act(async () => {
      if (!job.source_url) return;
      const fresh = await api.createFromUrl(job.source_url, job.options, false);
      channel.upsert(fresh);
      // the failed row goes away, so the list does not end up with two identical rows
      await api.deleteJob(job.id).catch(() => undefined);
    });

  const importResults = (path: string) =>
    act(async () => {
      const job = await api.importJob(path);
      channel.upsert(job);
      open(job.id);
    });

  return (
    <div className="mx-auto flex w-full max-w-5xl flex-col gap-5 px-4 py-6 sm:px-6 sm:py-8">
      <PageHeader
        title="Library"
        lede="Your analyses. Open one to study how each word was really said."
        actions={
          <>
            <Button icon={FolderInput} onPress={() => setImporting(true)}>
              Import results…
            </Button>
            <span {...tourAttr(TOUR.libraryNew)}>
              <LinkButton href={paths.newAnalysis()} variant="primary" icon={Plus}>
                New analysis
              </LinkButton>
            </span>
          </>
        }
      />

      {channelError && (
        <Notice tone="caution" title="No connection to the server" live>
          {channelError} The list updates again as soon as it is back.
        </Notice>
      )}
      {actionError && (
        <Notice tone="caution" title="That did not work" live>
          {actionError}
        </Notice>
      )}

      {!loaded ? (
        <p role="status" className="text-sm text-ink-2">
          Loading your analyses…
        </p>
      ) : jobs.length === 0 ? (
        <EmptyLibrary />
      ) : (
        <>
          {last && (
            <Card
              title="Continue where you left off"
              level={2}
              actions={
                <Button variant="primary" icon={Play} onPress={() => open(last.id)}>
                  Open
                </Button>
              }
            >
              <p className="truncate text-base text-ink">{last.source}</p>
            </Card>
          )}

          <section aria-labelledby="library-list-title" className="flex flex-col gap-3">
            <h2 id="library-list-title" className="sr-only">
              All analyses
            </h2>
            <div className="flex flex-wrap items-end gap-3">
              <SearchField
                label="Search analyses"
                value={query}
                onChange={setQuery}
                placeholder="Search analyses…"
                className="min-w-[min(14rem,100%)] flex-1"
              />
              <Select label="Sort" items={SORTS} value={sort} onChange={setSort} />
              {engines.length > 1 && (
                <Select
                  label="Engine"
                  items={[
                    { id: "all", label: "All engines" },
                    ...engines.map((id) => ({ id, label: engineLabel(id) })),
                  ]}
                  value={engine}
                  onChange={setEngine}
                />
              )}
            </div>
            <p className="text-sm text-ink-2" aria-live="polite">
              {visible.length === jobs.length
                ? analyses(jobs.length)
                : `${visible.length} of ${analyses(jobs.length)}`}
            </p>

            <GridList
              aria-label="Your analyses"
              items={visible}
              onAction={(key) => open(String(key))}
              renderEmptyState={() => (
                <div className="flex flex-col items-center gap-2 p-6 text-center text-ink-2">
                  <p>No analysis matches “{query}”.</p>
                  <Button size="sm" onPress={() => setQuery("")}>
                    Clear the search
                  </Button>
                </div>
              )}
              className="flex flex-col gap-2 outline-none"
            >
              {(job) => (
                <GridListItem
                  id={job.id}
                  textValue={job.source}
                  className={cn(
                    "rounded-card bg-surface p-3 shadow-1 outline-none",
                    "data-[hovered]:bg-surface-2 data-[focus-visible]:outline-3 data-[focus-visible]:outline-offset-2 data-[focus-visible]:outline-focus",
                  )}
                >
                  <JobRow
                    job={job}
                    olderRules={(rulesVersion(job) ?? newestRules) < newestRules}
                    onOpen={() => open(job.id)}
                    onCancel={() => act(() => api.cancelJob(job.id))}
                    onRetry={() => retry(job)}
                    onDelete={() => setDeleting(job)}
                  />
                </GridListItem>
              )}
            </GridList>
          </section>
        </>
      )}

      <ConfirmDialog
        title={deleting?.imported ? `Remove “${deleting.source}” from the list?` : `Delete “${deleting?.source}”?`}
        isOpen={deleting !== null}
        onOpenChange={(isOpen) => !isOpen && setDeleting(null)}
        confirmLabel={deleting?.imported ? "Remove" : "Delete"}
        destructive
        onConfirm={async () => {
          const job = deleting;
          if (job) await act(() => api.deleteJob(job.id));
        }}
      >
        {deleting?.imported
          ? "This only takes it off the list. The imported folder stays on disk, untouched."
          : "This deletes the results of this analysis: transcript, phones and report. Your original video or audio file is not touched."}
      </ConfirmDialog>

      <AppDialog title="Import existing results" isOpen={importing} onOpenChange={setImporting} size="lg">
        {(close) => (
          <div className="flex flex-col gap-3">
            <p className="text-sm text-ink-2">
              Pick a folder that contains <code>analysis.json</code>, for instance the <code>out/</code> that{" "}
              <code>phonotrainer analyze</code> left behind. Nothing is analyzed again.
            </p>
            <PathBrowser
              mode="dir"
              onPick={(path) => {
                close();
                void importResults(path);
              }}
            />
          </div>
        )}
      </AppDialog>
    </div>
  );
}

interface RowProps {
  job: Job;
  olderRules: boolean;
  onOpen: () => void;
  onCancel: () => void;
  onRetry: () => void;
  onDelete: () => void;
}

function JobRow({ job, olderRules, onOpen, onCancel, onRetry, onDelete }: RowProps) {
  const status = STATUS[job.status];
  const StatusIcon = status.icon;
  const engine = engineLabel(jobEngine(job));
  const rules = rulesVersion(job);
  const meta = [
    job.summary?.duration != null ? fmtDuration(job.summary.duration) : null,
    engine || null,
    job.summary ? `${job.summary.words} words` : null,
    relativeTime(job.created),
  ].filter(Boolean);

  return (
    <div className="flex items-start gap-3">
      {/* a phone puts the status above the name instead of in a column of its own */}
      <div className="flex min-w-0 flex-1 flex-col gap-1 sm:flex-row sm:items-start sm:gap-3">
        <span className="flex shrink-0 items-center gap-1.5 pt-0.5 text-sm font-semibold text-ink sm:w-28">
          <StatusIcon
            size={18}
            aria-hidden="true"
            className={cn("shrink-0", status.spins && "motion-ok:animate-spin")}
          />
          {status.label}
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <span
            className="line-clamp-2 break-words text-base font-semibold text-ink sm:line-clamp-1"
            title={job.source}
          >
            {job.source}
          </span>
          <span className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-ink-2">
            {meta.map((part, index) => (
              <span key={index} className="flex items-center gap-2">
                {index > 0 && <span aria-hidden="true">·</span>}
                {part}
              </span>
            ))}
            {job.imported && <Chip tone="muted">imported</Chip>}
            {rules !== null && (
              <Chip tone={olderRules ? "outline" : "muted"}>
                {olderRules ? `older rules (v${rules}): re-analyze to update` : `rules v${rules}`}
              </Chip>
            )}
          </span>
          {job.status === "running" && (
            <ProgressBar
              label={`Progress of ${job.source}`}
              hideLabel
              value={job.percent}
              valueText={`${job.percent} % · ${stageSummary(job)}`}
              className="mt-1 max-w-xl"
            />
          )}
          {job.status === "queued" && (
            <span className="text-sm text-ink-2">Waiting in line: analyses run one at a time.</span>
          )}
          {job.status === "error" && job.error && (
            <span className="line-clamp-2 text-sm text-ink">
              <span className="font-semibold">What went wrong: </span>
              {job.error}
            </span>
          )}
        </div>
      </div>
      {job.status === "error" && job.source_url && (
        <Button size="sm" icon={RotateCcw} onPress={onRetry}>
          Retry
        </Button>
      )}
      <MenuTrigger>
        <IconButton icon={MoreHorizontal} label={`More actions for ${job.source}`} size="sm" variant="quiet" />
        <Popover placement="bottom end">
          <Menu aria-label={`Actions for ${job.source}`}>
            <MenuItem onAction={onOpen}>
              <Play size={16} aria-hidden="true" /> Open
            </MenuItem>
            {job.has_report && (
              <MenuItem href={api.reportUrl(job.id)} target="_blank" rel="noreferrer">
                <ExternalLink size={16} aria-hidden="true" /> Open the report (new tab)
              </MenuItem>
            )}
            {isActive(job) && (
              <MenuItem onAction={onCancel}>
                <CircleStop size={16} aria-hidden="true" /> Cancel the analysis
              </MenuItem>
            )}
            {job.status === "error" && job.source_url && (
              <MenuItem onAction={onRetry}>
                <RotateCcw size={16} aria-hidden="true" /> Retry the download
              </MenuItem>
            )}
            <MenuSeparator />
            <MenuItem onAction={onDelete}>
              <Trash2 size={16} aria-hidden="true" />
              {job.imported ? "Remove from the list…" : "Delete…"}
            </MenuItem>
          </Menu>
        </Popover>
      </MenuTrigger>
    </div>
  );
}

/** First visit: what this is for, three first steps, and the tour. */
function EmptyLibrary() {
  return (
    <EmptyState
      icon={Clapperboard}
      title="Your library is empty"
      actions={
        <>
          <LinkButton href={paths.newAnalysis()} variant="primary" icon={Plus}>
            Analyze your first clip
          </LinkButton>
          <Button onPress={() => startTour()}>Take the 1-minute tour</Button>
        </>
      }
    >
      <p>
        Start with a short clip (30–60 seconds) of natural dialogue. You will see every word as the dictionary
        says it and as it was actually said.
      </p>
      <ol className="mt-4 flex flex-col gap-1 text-left text-sm text-ink-2">
        <li>
          <strong className="text-ink">1. Add a clip</strong> — a file on this computer or a YouTube link.
        </li>
        <li>
          <strong className="text-ink">2. Open a word</strong> — listen to it, then compare the dictionary form
          with what was said.
        </li>
        <li>
          <strong className="text-ink">3. Learn the pattern</strong> — every change has a name, an example and
          advice on whether to copy it.
        </li>
      </ol>
    </EmptyState>
  );
}
