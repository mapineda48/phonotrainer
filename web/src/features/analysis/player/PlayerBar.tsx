/** Playback controls: what gets touched every few seconds while studying. */

import { Pause, Play, Repeat, X } from "lucide-react";

import { tourAttr, TOUR } from "../../../didactic/tour-ids";
import { fmtTime } from "../../../lib/format";
import { useTimeSelector } from "../../../player/clock";
import { usePlayer } from "../../../player/PlayerProvider";
import { Chip, IconButton, Segmented, ToggleButton } from "../../../ui";

export const SPEEDS = [
  { id: "0.5", label: "0.5×" },
  { id: "0.75", label: "0.75×" },
  { id: "1", label: "1×" },
] as const;

export type SpeedId = (typeof SPEEDS)[number]["id"];

export const speedId = (rate: number): SpeedId =>
  rate <= 0.5 ? "0.5" : rate < 1 ? "0.75" : "1";

export function SpeedControl({ label = "Speed", hideLabel }: { label?: string; hideLabel?: boolean }) {
  const player = usePlayer();
  return (
    <Segmented
      label={label}
      hideLabel={hideLabel}
      size="sm"
      options={SPEEDS}
      value={speedId(player.rate)}
      onChange={(value) => player.setRate(Number(value))}
    />
  );
}

interface Props {
  duration: number;
  /** False when the analysis was imported without audio: nothing to play. */
  enabled: boolean;
  /** What is bounded right now ("the word “that”"), or null. */
  spanLabel: string | null;
  /** The waveform, injected so the bar does not depend on how it is drawn. */
  children?: React.ReactNode;
}

export function PlayerBar({ duration, enabled, spanLabel, children }: Props) {
  const player = usePlayer();
  const time = useTimeSelector(player.clock, (t) => Math.round(t * 10) / 10);

  return (
    <div
      role="group"
      aria-label="Playback"
      className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-line bg-surface px-4 py-2.5 sm:gap-x-4"
    >
      <IconButton
        icon={player.playing ? Pause : Play}
        label={player.playing ? "Pause" : "Play"}
        variant="primary"
        isDisabled={!enabled}
        onPress={() => player.toggle()}
        className="pointer-coarse:size-11"
      />
      {/* on a phone the waveform takes the rest of the first row and the time moves to
          the second, with the speed; only text moves, so the focus order is unchanged */}
      <span className="text-sm tabular-nums text-ink max-sm:order-1" aria-label={`Time ${fmtTime(time)} of ${fmtTime(duration)}`}>
        {fmtTime(time)} <span className="text-ink-muted">/ {fmtTime(duration)}</span>
      </span>
      {children}
      <div className="flex flex-wrap items-center gap-3 max-sm:order-2" {...tourAttr(TOUR.playerSpeed)}>
        <SpeedControl hideLabel />
        <ToggleButton
          size="sm"
          icon={Repeat}
          isSelected={player.loop}
          isDisabled={!enabled}
          onChange={(value) => player.setLoop(value)}
        >
          {/* a phone keeps the name for screen readers and shows the icon only */}
          <span className="max-sm:sr-only">Loop</span>
        </ToggleButton>
      </div>
      {spanLabel && (
        <Chip tone="muted" className="gap-1 pe-0.5 max-sm:order-3">
          Playing only {spanLabel}
          <IconButton icon={X} size="sm" label="Play everything again" onPress={() => player.clearSpan()} />
        </Chip>
      )}
    </div>
  );
}
