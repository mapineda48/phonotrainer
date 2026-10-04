/** The original video, docked over the transcript and synced to the player.
 *
 *  Audio is always in charge (it is the WAV that was analyzed); the video runs muted
 *  behind it and is corrected whenever it drifts by more than 200 ms. */

import { X } from "lucide-react";
import { useEffect, useRef } from "react";

import { useTimeSelector } from "../../../player/clock";
import { usePlayer } from "../../../player/PlayerProvider";
import { IconButton } from "../../../ui";

const MAX_DRIFT = 0.2;

export function SyncedVideo({ src }: { src: string }) {
  const player = usePlayer();
  const ref = useRef<HTMLVideoElement | null>(null);

  // check the drift 4 times per second, not on every frame
  const checkpoint = useTimeSelector(player.clock, (time) => Math.floor(time * 4));

  useEffect(() => {
    const video = ref.current;
    if (!video) return;
    const target = player.clock.getSnapshot();
    if (Math.abs(video.currentTime - target) > MAX_DRIFT) video.currentTime = target;
  }, [checkpoint, player.clock]);

  useEffect(() => {
    const video = ref.current;
    if (!video) return;
    video.playbackRate = player.rate;
    if (player.playing) {
      const promise = video.play() as Promise<void> | undefined;
      promise?.catch(() => undefined);
    } else {
      video.pause();
    }
  }, [player.playing, player.rate]);

  return (
    <video
      ref={ref}
      src={src}
      muted
      playsInline
      preload="metadata"
      aria-label="Original video, muted and synced to the audio"
      className="block w-full rounded-control bg-ink"
    />
  );
}

/** Floats over the transcript, so it rides along instead of pushing the text down. */
export function VideoDock({ src, onClose }: { src: string; onClose: () => void }) {
  return (
    <div className="absolute bottom-3 right-3 z-20 w-[min(22rem,60%)] rounded-card bg-surface p-1.5 shadow-2 ring-1 ring-line-strong">
      <SyncedVideo src={src} />
      <IconButton
        icon={X}
        label="Hide video"
        size="sm"
        variant="secondary"
        onPress={onClose}
        className="absolute right-2.5 top-2.5"
      />
    </div>
  );
}
