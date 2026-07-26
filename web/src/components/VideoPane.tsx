/** Video original sincronizado con el reproductor.
 *
 *  El audio manda siempre (es el WAV que se analizó); el video va mudo detrás y
 *  se corrige si se desfasa más de 200 ms. */

import { useEffect, useRef } from "react";

import { useTimeSelector } from "../player/clock";
import { usePlayer } from "../player/PlayerProvider";

const MAX_DRIFT = 0.2;

export function VideoPane({ src }: { src: string }) {
  const player = usePlayer();
  const ref = useRef<HTMLVideoElement | null>(null);

  // Comprobamos el desfase 4 veces por segundo, no en cada frame.
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

  return <video ref={ref} src={src} className="video" muted playsInline preload="metadata" />;
}
