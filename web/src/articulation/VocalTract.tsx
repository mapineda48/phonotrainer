/** The mouth on screen: a canvas driven by the playback clock.
 *
 *  The pose is never React state. At 60 fps that would re-render the panel on
 *  every frame — the same reason the transcript reads the time through an
 *  external store. Here the animation loop reads the clock, asks `track.ts`
 *  for the pose and hands it straight to the engine; React only hears about
 *  the *phone* changing, which happens a handful of times per second.
 */

import { useCallback, useEffect, useRef, useState } from "react";

import { useTimeSelector } from "../player/clock";
import { usePlayer } from "../player/PlayerProvider";
import {
  EYE,
  MANDIBLE_SECTION,
  NASAL_CAVITY,
  UPPER_FACE,
  UPPER_TEETH,
  VIEW_MOUTH,
  type Vec2,
  type ViewBox,
} from "./anatomy";
import { readPalette, type Palette } from "./palette";
import { REST_POSE, type Pose } from "./pose";
import { createTractView, type TractView } from "./scene";
import { buildTract } from "./tract";
import { phoneAt, poseAt, type Track } from "./track";

/** Is WebGL there at all? Asked on a throwaway canvas and *before* importing
 *  three, so a browser without it (or jsdom in the tests) never downloads an
 *  engine it cannot use. */
function hasWebGL(): boolean {
  if (typeof document === "undefined") return false;
  try {
    const probe = document.createElement("canvas");
    return Boolean(probe.getContext("webgl2") ?? probe.getContext("webgl"));
  } catch {
    return false;
  }
}

interface Props {
  track: Track;
  /** Instant to show when the audio is stopped: the phone the user picked.
   *  Null means "wherever the playhead is". */
  previewTime: number | null;
  height: number;
  /** Read out by screen readers, and the title of the canvas. */
  label: string;
  /** What slice of the head to frame. The default is the panel close-up. */
  view?: ViewBox;
}

export function VocalTract({ track, previewTime, height, label, view = VIEW_MOUTH }: Props) {
  const player = usePlayer();
  const boxRef = useRef<HTMLDivElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const viewRef = useRef<TractView | null>(null);
  const [webgl] = useState(hasWebGL);
  const [ready, setReady] = useState(false);
  const [palette, setPalette] = useState<Palette | null>(null);

  // The palette comes from the stylesheet, so the tract follows the app's
  // theme — including a switch of the system theme while the page is open.
  useEffect(() => {
    const read = () => setPalette(readPalette(boxRef.current));
    read();
    const media = window.matchMedia?.("(prefers-color-scheme: dark)");
    media?.addEventListener?.("change", read);
    return () => media?.removeEventListener?.("change", read);
  }, []);

  // Built once. The palette is applied afterwards through setPalette(), so a
  // theme switch never costs a rebuilt scene.
  useEffect(() => {
    if (!webgl) return;
    const canvas = canvasRef.current;
    if (!canvas) return;
    let disposed = false;
    createTractView(canvas, readPalette(boxRef.current), view)
      .then((created) => {
        if (!created) return;
        if (disposed) {
          created.dispose();
          return;
        }
        viewRef.current = created;
        setReady(true);
      })
      .catch(() => undefined); // no engine: the SVG below takes over
    return () => {
      disposed = true;
      viewRef.current?.dispose();
      viewRef.current = null;
      setReady(false);
    };
    // `view` is read once, when the camera is built: changing the framing of a
    // live panel is not a thing the interface does.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [webgl]);

  useEffect(() => {
    if (palette) viewRef.current?.setPalette(palette);
  }, [palette, ready]);

  /** One frame, right now. Resizing needs it as much as the clock does: the
   *  canvas is cleared when the drawing buffer changes size. */
  const playing = player.playing;
  const clock = player.clock;
  const renderOnce = useCallback(() => {
    const engine = viewRef.current;
    if (!engine) return;
    const now = clock.getSnapshot();
    const time = playing ? now : (previewTime ?? now);
    const phone = phoneAt(track, time);
    engine.render({
      pose: poseAt(track, time),
      manner: phone?.articulation?.manner ?? null,
      // Wall clock, so the airflow keeps moving at 0.5× too.
      time: performance.now() / 1000,
      active: playing,
    });
  }, [clock, playing, previewTime, track]);

  useEffect(() => {
    const box = boxRef.current;
    if (!box || !ready) return;
    const resize = () => {
      viewRef.current?.setSize(box.clientWidth, box.clientHeight);
      renderOnce();
    };
    resize();
    const observer = new ResizeObserver(resize);
    observer.observe(box);
    return () => observer.disconnect();
  }, [ready, renderOnce]);

  // The animation loop runs while the audio does; stopped, it draws one frame
  // and gets out of the way.
  useEffect(() => {
    if (!ready) return;
    let frame = 0;
    const loop = () => {
      renderOnce();
      frame = requestAnimationFrame(loop);
    };
    if (playing) loop();
    else renderOnce();
    return () => cancelAnimationFrame(frame);
  }, [ready, playing, renderOnce]);

  return (
    <div
      ref={boxRef}
      className="tract"
      style={{ height }}
      role="img"
      aria-label={label}
      title={label}
    >
      {webgl ? (
        <canvas ref={canvasRef} className="tract__canvas" />
      ) : (
        <TractSvg track={track} previewTime={previewTime} view={view} />
      )}
    </div>
  );
}

/** Same geometry, drawn as plain SVG: without WebGL the mouth still moves,
 *  just without the airflow or the highlights. It is also what the tests see,
 *  and what the lab page shows when it is asked to check this path. */
export function TractSvg({
  track,
  previewTime,
  view = VIEW_MOUTH,
}: {
  track: Track;
  previewTime: number | null;
  view?: ViewBox;
}) {
  const player = usePlayer();
  // 25 frames per second is plenty for a fallback, and it keeps the DOM churn
  // an order of magnitude below the animation loop above.
  const tick = useTimeSelector(player.clock, (time) => Math.round(time * 25));
  const time = player.playing ? tick / 25 : (previewTime ?? tick / 25);
  const pose: Pose = track.keys.length > 0 ? poseAt(track, time) : REST_POSE;
  const shapes = buildTract(pose);

  // SVG counts y downwards and the anatomy counts it upwards.
  const path = (points: readonly Vec2[]): string =>
    `M${points.map(([x, y]) => `${x.toFixed(1)},${(100 - y).toFixed(1)}`).join("L")}Z`;

  const box = `${view.x - view.width / 2} ${100 - view.y - view.height / 2} ${view.width} ${view.height}`;

  return (
    <svg viewBox={box} className="tract__svg" aria-hidden="true">
      <path
        d={`${path(UPPER_FACE)} ${path(NASAL_CAVITY)}`}
        fill="var(--tract-tissue)"
        fillRule="evenodd"
        stroke="var(--tract-line)"
        strokeWidth={0.4}
      />
      <path
        d={path(shapes.jawTissue)}
        fill="var(--tract-tissue)"
        stroke="var(--tract-line)"
        strokeWidth={0.4}
      />
      <path d={path(MANDIBLE_SECTION)} fill="var(--tract-bone)" />
      <path
        d={path(shapes.velum)}
        fill="var(--tract-tissue)"
        stroke="var(--tract-line)"
        strokeWidth={0.35}
      />
      <path d={path(UPPER_TEETH)} fill="var(--tract-bone)" />
      <path d={path(shapes.lowerTeeth)} fill="var(--tract-bone)" />
      <path
        d={path(shapes.tongue)}
        fill="var(--tract-tongue)"
        stroke="var(--tract-line)"
        strokeWidth={0.45}
        data-testid="tract-tongue"
      />
      <path
        d={`${path(shapes.upperLip)} ${path(shapes.lowerLip)}`}
        fill="var(--tract-lip)"
        stroke="var(--tract-line)"
        strokeWidth={0.35}
      />
      <path d={path(EYE)} fill="var(--tract-line)" />
    </svg>
  );
}
