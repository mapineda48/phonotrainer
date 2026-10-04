/** Play a span of ANY analysis's audio, outside the analysis workspace (Learn examples,
 *  Practice items, Insights rows). One clip plays at a time across the whole app; the
 *  workspace keeps its own player (player/PlayerProvider).
 *
 *    <ClipPlayer jobId={id} start={2.31} end={2.52} label="Play “get it”" />
 */

import { Pause, Play } from "lucide-react";
import { useCallback, useEffect, useSyncExternalStore } from "react";
import { Button as AriaButton } from "react-aria-components";

import { api, type AudioTrack } from "../api";
import { cn } from "../ui/cn";
import { Tip } from "../ui/Tooltip";

export interface Clip {
  jobId: string;
  start: number;
  end: number;
  track?: AudioTrack;
  /** Playback rate (0.5 for slow listening). */
  rate?: number;
  /** Seconds added before/after so the edges are audible (default 0.05 / 0.08). */
  padBefore?: number;
  padAfter?: number;
}

const clipKey = (clip: Clip) =>
  `${clip.jobId}|${clip.track ?? "mix"}|${clip.start}|${clip.end}|${clip.rate ?? 1}`;

/** The single shared <audio> element and which clip it is playing. */
class ClipAudio {
  private audio: HTMLAudioElement | null = null;
  private current: string | null = null;
  private stopAt = 0;
  private listeners = new Set<() => void>();
  private raf = 0;

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  playing = () => this.current;

  private emit() {
    for (const listener of this.listeners) listener();
  }

  private element(): HTMLAudioElement {
    if (!this.audio) {
      this.audio = new Audio();
      this.audio.preload = "auto";
      this.audio.addEventListener("ended", () => this.stop());
      this.audio.addEventListener("pause", () => {
        if (this.current) this.stop();
      });
    }
    return this.audio;
  }

  play(clip: Clip) {
    const audio = this.element();
    const src = api.audioUrl(clip.jobId, clip.track ?? "mix");
    const start = Math.max(0, clip.start - (clip.padBefore ?? 0.05));
    this.stopAt = clip.end + (clip.padAfter ?? 0.08);
    this.current = clipKey(clip);
    this.emit();
    const begin = () => {
      try {
        audio.currentTime = start;
      } catch {
        /* metadata not ready yet: the loadedmetadata handler retries */
      }
      audio.playbackRate = clip.rate ?? 1;
      void audio.play()?.catch(() => this.stop());
      this.watch();
    };
    if (!audio.src.endsWith(src)) {
      audio.src = src;
      audio.addEventListener("loadedmetadata", begin, { once: true });
      audio.load?.();
    } else {
      begin();
    }
  }

  private watch() {
    cancelAnimationFrame(this.raf);
    const tick = () => {
      const audio = this.audio;
      if (!audio || !this.current) return;
      if (audio.currentTime >= this.stopAt) {
        this.stop();
        return;
      }
      this.raf = requestAnimationFrame(tick);
    };
    this.raf = requestAnimationFrame(tick);
  }

  stop() {
    cancelAnimationFrame(this.raf);
    const wasPlaying = this.current !== null;
    this.current = null;
    if (this.audio && !this.audio.paused) this.audio.pause();
    if (wasPlaying) this.emit();
  }
}

export const clipAudio = new ClipAudio();

/** Is this clip the one playing? Plus play/stop for it. */
export function useClip(clip: Clip) {
  const key = clipKey(clip);
  const current = useSyncExternalStore(clipAudio.subscribe, clipAudio.playing, () => null);
  const isPlaying = current === key;
  const toggle = useCallback(() => {
    if (clipAudio.playing() === key) clipAudio.stop();
    else clipAudio.play(clip);
    // the clip is fully described by its key
  }, [key]);
  return { isPlaying, toggle, play: () => clipAudio.play(clip), stop: () => clipAudio.stop() };
}

interface ClipPlayerProps extends Clip {
  /** Accessible name and tooltip ("Play “get it”"). */
  label: string;
  /** Visible text next to the icon; omit for an icon-only button. */
  children?: React.ReactNode;
  size?: "sm" | "md";
  className?: string;
}

export function ClipPlayer({ label, children, size = "md", className, ...clip }: ClipPlayerProps) {
  const { isPlaying, toggle } = useClip(clip);
  // stop when the button that started the clip goes away
  useEffect(() => () => {
    if (clipAudio.playing() === clipKey(clip)) clipAudio.stop();
  }, []);
  const Icon = isPlaying ? Pause : Play;
  const button = (
    <AriaButton
      onPress={toggle}
      aria-label={children ? undefined : isPlaying ? `Stop: ${label}` : label}
      aria-pressed={isPlaying}
      className={cn(
        "inline-flex shrink-0 items-center justify-center gap-1.5 rounded-control font-medium outline-none",
        "shadow-[inset_0_0_0_1px_var(--line-strong)] transition-colors duration-(--dur-fast)",
        isPlaying ? "bg-ink text-page" : "bg-surface text-ink hover:bg-surface-2",
        children ? (size === "sm" ? "h-8 px-2.5 text-sm" : "h-10 px-3 text-sm") : size === "sm" ? "size-8" : "size-10",
        className,
      )}
    >
      <Icon size={size === "sm" ? 15 : 18} aria-hidden="true" fill={isPlaying ? "none" : "currentColor"} />
      {children}
    </AriaButton>
  );
  return children ? button : <Tip content={label}>{button}</Tip>;
}
