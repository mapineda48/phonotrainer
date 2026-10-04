/** The workspace header: where you came from, what this clip is, and the controls that
 *  apply to the whole analysis (search, which track to hear, video, follow, more). */

import { ArrowLeft, ExternalLink, Keyboard, MoreHorizontal, Video } from "lucide-react";
import { MenuTrigger } from "react-aria-components";
import { useLocation } from "wouter";

import { api, type AudioTrack } from "../../../api";
import { Explain } from "../../../didactic/Explain";
import { fmtDuration, plural } from "../../../lib/format";
import { paths } from "../../../paths";
import type { Analysis, Job } from "../../../types";
import {
  Button,
  IconButton,
  LinkButton,
  Menu,
  MenuItem,
  Popover,
  SearchField,
  Segmented,
  ToggleButton,
} from "../../../ui";
import { engineOf } from "../lib/words";

const BACK: Record<string, { label: string; href: () => string }> = {
  insights: { label: "Back to Insights", href: () => paths.insights() },
  learn: { label: "Back to Learn", href: () => paths.learn() },
  practice: { label: "Back to Practice", href: () => paths.practice() },
};

interface Props {
  job: Job;
  analysis: Analysis;
  from: string | null;
  query: string;
  onQuery: (value: string) => void;
  onSearchNext: () => void;
  /** Words matching the search (null without a search). */
  matches: number | null;
  track: AudioTrack;
  onTrack: (track: AudioTrack) => void;
  hasVideo: boolean;
  showVideo: boolean;
  onToggleVideo: () => void;
  follow: boolean;
  onFollow: (follow: boolean) => void;
  onShortcuts: () => void;
}

export function TopBar({
  job,
  analysis,
  from,
  query,
  onQuery,
  onSearchNext,
  matches,
  track,
  onTrack,
  hasVideo,
  showVideo,
  onToggleVideo,
  follow,
  onFollow,
  onShortcuts,
}: Props) {
  const [, navigate] = useLocation();
  const back = from ? BACK[from] : undefined;
  const words = analysis.segments.reduce((total, segment) => total + segment.words.length, 0);

  return (
    <header className="flex flex-col gap-3 border-b border-line bg-page px-4 pb-3 pt-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          {back ? (
            <Button
              size="sm"
              variant="quiet"
              icon={ArrowLeft}
              className="w-fit"
              onPress={() => {
                // the page we came from keeps its filters in its own URL
                if (window.history.length > 1) window.history.back();
                else navigate(back.href());
              }}
            >
              {back.label}
            </Button>
          ) : (
            <LinkButton href={paths.library()} size="sm" variant="quiet" icon={ArrowLeft} className="w-fit">
              Library
            </LinkButton>
          )}
          {/* a phone shows the long names of downloaded clips on two lines, not cut to a few words */}
          <h1 className="line-clamp-2 break-words text-xl font-bold text-ink sm:text-2xl lg:line-clamp-1">{job.source}</h1>
          <p className="text-sm text-ink-2">
            {fmtDuration(analysis.meta.duration)} · {plural(analysis.segments.length, "phrase")} · {plural(words, "word")} ·{" "}
            <Explain term="engine">{engineOf(analysis)} engine</Explain>
          </p>
        </div>
        <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto">
          <SearchField
            label="Search for a word in the transcript"
            placeholder="Search a word…"
            value={query}
            onChange={onQuery}
            onSubmit={onSearchNext}
            className="w-full sm:w-52"
          />
          {matches !== null && (
            <span role="status" className="text-sm tabular-nums text-ink-2">
              {matches} {matches === 1 ? "match" : "matches"}
            </span>
          )}
        </div>
      </div>

      <div className="flex flex-wrap items-end gap-x-4 gap-y-2">
        {job.has_dialogue_audio && (
          <div className="flex items-end gap-1">
            <Segmented
              label="Listen to"
              hideLabel
              size="sm"
              options={[
                { id: "mix", label: "Original mix" },
                { id: "dialogue", label: "Dialogue only" },
              ]}
              value={track}
              onChange={onTrack}
            />
            <Explain term="separation" className="mb-1" />
          </div>
        )}
        {hasVideo && (
          <ToggleButton size="sm" icon={Video} isSelected={showVideo} onChange={onToggleVideo}>
            Video
          </ToggleButton>
        )}
        <ToggleButton size="sm" isSelected={follow} onChange={onFollow}>
          Follow playback
        </ToggleButton>
        <MenuTrigger>
          <IconButton icon={MoreHorizontal} label="More actions" size="sm" variant="secondary" noTooltip />
          <Popover placement="bottom end">
            <Menu
              aria-label="More actions"
              onAction={(key) => {
                if (key === "shortcuts") onShortcuts();
              }}
            >
              {job.has_report && (
                <MenuItem id="report" href={api.reportUrl(job.id)} target="_blank" rel="noreferrer" textValue="Open report.html">
                  <ExternalLink size={16} aria-hidden="true" />
                  Open report.html (new tab)
                </MenuItem>
              )}
              <MenuItem id="shortcuts" textValue="Keyboard shortcuts">
                <Keyboard size={16} aria-hidden="true" />
                Keyboard shortcuts
              </MenuItem>
            </Menu>
          </Popover>
        </MenuTrigger>
      </div>
    </header>
  );
}
