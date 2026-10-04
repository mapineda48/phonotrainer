/** New analysis: pick the material (a file on this computer, an upload, or a YouTube
 *  link), decide how to analyze it, start, and watch the pipeline's steps. Every option
 *  says in one line what it changes; the engine notes come from the backend, license
 *  included, so the choice is honest. */

import {
  ArrowLeft,
  CircleStop,
  FolderInput,
  FolderOpen,
  HardDrive,
  Link2,
  Play,
  RotateCcw,
  Upload,
} from "lucide-react";
import { useState } from "react";
import { DropZone, FileTrigger, type FileDropItem } from "react-aria-components";
import { useLocation } from "wouter";

import { api } from "../../api";
import { Explain } from "../../didactic/Explain";
import { useJob } from "../../hooks/useJobs";
import { useJobsChannel } from "../../jobs/JobsProvider";
import { fmtBytes } from "../../lib/format";
import { paths } from "../../paths";
import { useReference } from "../../reference";
import type { Job, JobOptions } from "../../types";
import {
  AppDialog,
  Button,
  ButtonRow,
  Card,
  cn,
  Disclosure,
  JobStepper,
  LinkButton,
  Notice,
  PageHeader,
  RadioCard,
  RadioCardGroup,
  Segmented,
  Select,
  Switch,
  Tab,
  TabList,
  TabPanel,
  Tabs,
  TextField,
} from "../../ui";
import { engineLabel, rememberJob } from "./jobInfo";
import { PathBrowser } from "./PathBrowser";

type Source = "path" | "upload" | "youtube";
type Attraction = "auto" | "on" | "off";

const ENGINE_TITLE: Record<string, string> = {
  timit61: "TIMIT-61 · recommended",
  espeak: "espeak",
};

const MODEL_HINT: Record<string, string> = {
  tiny: "fastest, least accurate",
  base: "fast",
  small: "balanced, the default",
  medium: "most accurate, slowest",
};

const toAttraction = (value: boolean | null | undefined): Attraction =>
  value == null ? "auto" : value ? "on" : "off";
const fromAttraction = (value: Attraction): boolean | null =>
  value === "on" ? true : value === "off" ? false : null;

const message = (err: unknown) => (err instanceof Error ? err.message : String(err));
const isMedia = (file: File) => /^(video|audio)\//.test(file.type) || file.type === "";

export function NewAnalysisPage() {
  const [started, setStarted] = useState<Job | null>(null);
  if (started) return <Started initial={started} onAnother={() => setStarted(null)} />;
  return <NewAnalysisForm onStarted={setStarted} />;
}

function NewAnalysisForm({ onStarted }: { onStarted: (job: Job) => void }) {
  // Models, engines, notes and defaults are published by the backend: a new engine
  // shows up here without touching the interface.
  const reference = useReference();
  const channel = useJobsChannel();
  const [, navigate] = useLocation();
  const [options, setOptions] = useState<JobOptions>(reference.options.defaults);
  const [source, setSource] = useState<Source>("path");
  const [path, setPath] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [url, setUrl] = useState("");
  const [audioOnly, setAudioOnly] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [browsing, setBrowsing] = useState<"media" | "dir" | null>(null);

  const set = <K extends keyof JobOptions>(key: K, value: JobOptions[K]) =>
    setOptions((current) => ({ ...current, [key]: value }));

  const ready =
    (source === "path" && path.trim() !== "") ||
    (source === "upload" && file !== null) ||
    (source === "youtube" && url.trim() !== "");

  const run = async (action: () => Promise<Job>, opensAnalysis = false) => {
    setBusy(true);
    setError(null);
    try {
      const job = await action();
      // the POST response is applied at once; the server event confirms it
      channel.upsert(job);
      rememberJob(job.id);
      if (opensAnalysis) navigate(paths.analysis(job.id));
      else onStarted(job);
    } catch (err) {
      setError(message(err));
    } finally {
      setBusy(false);
    }
  };

  const start = () => {
    if (!ready || busy) return;
    if (source === "path") void run(() => api.createJob(path.trim(), options));
    if (source === "upload" && file) void run(() => api.uploadJob(file, options));
    if (source === "youtube") void run(() => api.createFromUrl(url.trim(), options, audioOnly));
  };

  const chooseFile = (picked: File | undefined) => {
    if (!picked) return;
    if (!isMedia(picked)) {
      setError(`“${picked.name}” does not look like a video or audio file.`);
      return;
    }
    setError(null);
    setFile(picked);
  };

  const engines = reference.options.phone_engines.filter((engine) => engine !== "wav2vec2");
  const notes = reference.options.phone_engine_notes ?? {};

  return (
    <div className="mx-auto flex w-full max-w-4xl flex-col gap-5 px-6 py-8">
      <PageHeader
        eyebrow={
          <LinkButton href={paths.library()} variant="quiet" size="sm" icon={ArrowLeft} className="-ml-3 w-fit">
            Library
          </LinkButton>
        }
        title="New analysis"
        lede="Pick a clip of native English speech. Short, clear dialogue (30–60 seconds) teaches the most."
      />

      <Card title="1. Choose the clip" description="Where is the video or audio you want to study?">
        <Tabs selectedKey={source} onSelectionChange={(key) => setSource(key as Source)}>
          <TabList aria-label="Where the clip comes from">
            <Tab id="path">
              <HardDrive size={16} aria-hidden="true" /> On this computer
            </Tab>
            <Tab id="upload">
              <Upload size={16} aria-hidden="true" /> Upload a file
            </Tab>
            <Tab id="youtube">
              <Link2 size={16} aria-hidden="true" /> YouTube link
            </Tab>
          </TabList>

          <TabPanel id="path" className="pt-4">
            <div className="flex flex-wrap items-end gap-2">
              <TextField
                label="File path"
                value={path}
                onChange={setPath}
                placeholder="~/videos/episode.webm"
                description="The file stays where it is: nothing is copied."
                className="min-w-64 flex-1"
              />
              <Button icon={FolderOpen} onPress={() => setBrowsing("media")} className="mb-6">
                Browse…
              </Button>
            </div>
          </TabPanel>

          <TabPanel id="upload" className="pt-4">
            <DropZone
              aria-label="Drop a video or audio file"
              onDrop={async (event) => {
                const item = event.items.find((entry): entry is FileDropItem => entry.kind === "file");
                if (item) chooseFile(await item.getFile());
              }}
              className={cn(
                "flex flex-col items-center gap-3 rounded-card bg-surface-2 px-6 py-8 text-center outline-none",
                "shadow-[inset_0_0_0_2px_var(--line-strong)] [border-style:dashed]",
                "data-[drop-target]:bg-surface data-[drop-target]:shadow-[inset_0_0_0_3px_var(--ink)]",
              )}
            >
              {({ isDropTarget }) => (
                <>
                  <Upload size={28} aria-hidden="true" className="text-ink-2" />
                  <p className="text-base text-ink">
                    {isDropTarget ? "Release to choose this file" : "Drop a video or audio file here"}
                  </p>
                  <FileTrigger
                    acceptedFileTypes={["video/*", "audio/*"]}
                    onSelect={(files) => chooseFile(files ? Array.from(files)[0] : undefined)}
                  >
                    <Button size="sm">or choose a file…</Button>
                  </FileTrigger>
                  {file && (
                    <p className="text-sm text-ink" role="status">
                      Chosen: <strong>{file.name}</strong>
                      {file.size ? <span className="text-ink-2"> · {fmtBytes(file.size)}</span> : null}
                    </p>
                  )}
                  <p className="text-xs text-ink-muted">The file is copied into PhonoTrainer's workspace.</p>
                </>
              )}
            </DropZone>
          </TabPanel>

          <TabPanel id="youtube" className="flex flex-col gap-3 pt-4">
            <TextField
              label="YouTube URL"
              type="url"
              value={url}
              onChange={setUrl}
              placeholder="https://www.youtube.com/watch?v=…"
              description="Downloading may breach the platform's terms of service; that responsibility is yours."
            />
            <Switch
              isSelected={audioOnly}
              onChange={setAudioOnly}
              description="Faster, but there is no video to watch next to the transcript."
            >
              Download the audio only
            </Switch>
          </TabPanel>
        </Tabs>
      </Card>

      <Card title="2. Choose how to analyze it" description="The defaults suit most clips.">
        <div className="flex flex-col gap-5">
          <div className="flex flex-col gap-1.5">
            <RadioCardGroup
              label="Phone engine"
              value={options.phone_engine === "wav2vec2" ? "espeak" : options.phone_engine}
              onChange={(value) => set("phone_engine", value as JobOptions["phone_engine"])}
            >
              {engines.map((engine) => (
                <RadioCard key={engine} value={engine} title={ENGINE_TITLE[engine] ?? engineLabel(engine)}>
                  {notes[engine] ?? ""}
                </RadioCard>
              ))}
            </RadioCardGroup>
            <Explain term="engine" className="text-sm text-ink-2">
              What does the phone engine do?
            </Explain>
          </div>

          <div className="flex flex-col gap-1.5">
            <Switch
              isSelected={options.separate_dialogue ?? false}
              onChange={(value) => set("separate_dialogue", value)}
              description="Slower (about 0.7× the clip's length), but much better on films and series with music. You can still listen to the original mix."
            >
              Separate the dialogue from music and effects
            </Switch>
            <Explain term="separation" className="pl-14 text-sm text-ink-2">
              Why separate?
            </Explain>
          </div>

          <div className="flex flex-col gap-1.5">
            <Segmented<Attraction>
              label="Phonetic attraction"
              options={[
                { id: "auto", label: "Auto" },
                { id: "on", label: "On" },
                { id: "off", label: "Off" },
              ]}
              value={toAttraction(options.attraction)}
              onChange={(value) => set("attraction", fromAttraction(value))}
            />
            <p className="text-xs text-ink-muted">
              It undoes the recognizer's acoustic confusions. Auto follows the engine: on for espeak, off for
              TIMIT-61.
            </p>
            <Explain term="attraction" className="text-sm text-ink-2">
              What is attraction?
            </Explain>
          </div>

          <Select
            label="Speech model (Whisper)"
            items={reference.options.whisper_models.map((model) => ({
              id: model,
              label: model,
              description: MODEL_HINT[model],
            }))}
            value={options.whisper_model}
            onChange={(value) => set("whisper_model", value as JobOptions["whisper_model"])}
            description="It writes down the words; bigger models make fewer mistakes but take longer."
            className="w-fit"
          />

          <Disclosure title="Advanced" defaultExpanded={false}>
            <TextField
              label="Language code"
              value={options.language}
              onChange={(value) => set("language", value)}
              description="The spoken language. PhonoTrainer is built for English (en)."
              className="max-w-48"
            />
          </Disclosure>
        </div>
      </Card>

      {error && (
        <Notice tone="caution" title="The analysis could not start" live>
          {error}
        </Notice>
      )}

      <ButtonRow className="justify-end">
        <Button variant="primary" size="lg" icon={Play} isDisabled={!ready || busy} onPress={start}>
          {busy ? "Starting…" : "Start analysis"}
        </Button>
      </ButtonRow>

      <Card
        title="Already have results?"
        description={
          <>
            Import a folder that contains <code>analysis.json</code> (for instance the <code>out/</code> that{" "}
            <code>phonotrainer analyze</code> left behind) and study it here without analyzing again.
          </>
        }
      >
        <Button icon={FolderInput} onPress={() => setBrowsing("dir")}>
          Import results…
        </Button>
      </Card>

      <AppDialog
        title={browsing === "dir" ? "Import existing results" : "Choose a video or audio file"}
        isOpen={browsing !== null}
        onOpenChange={(open) => !open && setBrowsing(null)}
        size="lg"
      >
        {(close) =>
          browsing && (
            <PathBrowser
              mode={browsing}
              onPick={(picked) => {
                close();
                if (browsing === "dir") void run(() => api.importJob(picked), true);
                else {
                  setPath(picked);
                  setSource("path");
                }
              }}
            />
          )
        }
      </AppDialog>
    </div>
  );
}

/** After "Start analysis": the steps, live, and what to do next. */
function Started({ initial, onAnother }: { initial: Job; onAnother: () => void }) {
  const job = useJob(initial.id) ?? initial;
  const [retryError, setRetryError] = useState<string | null>(null);
  const active = job.status === "queued" || job.status === "running";

  const retry = async () => {
    if (!job.source_url) return;
    setRetryError(null);
    try {
      await api.createFromUrl(job.source_url, job.options, false);
      await api.deleteJob(job.id).catch(() => undefined);
    } catch (err) {
      setRetryError(message(err));
    }
  };

  const title =
    job.status === "done"
      ? "The analysis is ready"
      : job.status === "error"
        ? "The analysis failed"
        : job.status === "cancelled"
          ? "The analysis was cancelled"
          : "Analysis started";

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-5 px-6 py-8">
      <PageHeader
        title={title}
        lede={
          active
            ? "You can leave this page: the analysis keeps running and the library shows its progress. The first run loads about 2 GB of models, so it starts slowly."
            : undefined
        }
      />
      <Card title={job.source} description={`Engine: ${engineLabel(job.options.phone_engine) || "default"}`}>
        <JobStepper job={job} />
      </Card>

      {job.status === "error" && (
        <Notice tone="caution" title="What went wrong">
          {job.error ?? "Unknown error."}
        </Notice>
      )}
      {retryError && (
        <Notice tone="caution" title="The retry failed" live>
          {retryError}
        </Notice>
      )}

      <ButtonRow>
        {job.status === "done" ? (
          <LinkButton href={paths.analysis(job.id)} variant="primary" icon={Play}>
            Open the analysis
          </LinkButton>
        ) : (
          <LinkButton href={paths.analysis(job.id)} variant="secondary">
            Open the analysis view
          </LinkButton>
        )}
        {active && (
          <Button icon={CircleStop} onPress={() => void api.cancelJob(job.id)}>
            Cancel
          </Button>
        )}
        {job.status === "error" && job.source_url && (
          <Button icon={RotateCcw} onPress={() => void retry()}>
            Retry the download
          </Button>
        )}
        <Button variant="quiet" onPress={onAnother}>
          Analyze another clip
        </Button>
        <LinkButton href={paths.library()} variant="quiet">
          Back to the library
        </LinkButton>
      </ButtonRow>
    </div>
  );
}
