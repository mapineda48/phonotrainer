/** The mouth on screen: a canvas driven by the playback clock.
 *
 *  The pose is never React state. At 60 fps that would re-render the panel on
 *  every frame — the same reason the transcript reads the time through an
 *  external store. Here the animation loop reads the clock, asks `track.ts`
 *  for the pose and hands it straight to the engine; React only hears about
 *  the *phone* changing, which happens a handful of times per second.
 */

import { useCallback, useEffect, useRef, useState, type RefObject } from "react";

import "./articulation.css";
import { useTimeSelector } from "../player/clock";
import { usePlayer } from "../player/PlayerProvider";
import { LABELS, VIEW_MOUTH, type Vec2, type ViewBox } from "./anatomy";
import { closed, lowerTooth, staticOutlines } from "./outlines";
import { FALLBACK_PALETTE, mix, readPalette, samePalette, type Palette } from "./palette";
import { REST_POSE, type Pose } from "./pose";
import type { TractView } from "./scene";
import { LIP_EXPOSED_POINTS, buildTract } from "./tract";
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

/** Attributes that re-theme the page (Settings writes them on <html>, and a
 *  preview can set them on any ancestor). */
const THEME_ATTRIBUTES = ["data-theme", "data-palette", "data-motion", "class", "style"];

/** Calls `onChange` whenever the theme or the motion setting may have changed:
 *  the system preferences, or the attributes Settings puts on the page. */
function watchTheme(element: Element | null, onChange: () => void): () => void {
  const queries = ["(prefers-color-scheme: dark)", "(prefers-reduced-motion: reduce)"]
    .map((query) => (typeof window.matchMedia === "function" ? window.matchMedia(query) : null))
    .filter((query): query is MediaQueryList => query !== null);
  for (const query of queries) query.addEventListener?.("change", onChange);
  let observer: MutationObserver | null = null;
  if (typeof MutationObserver !== "undefined") {
    observer = new MutationObserver(onChange);
    for (let node: Element | null = element ?? document.documentElement; node; node = node.parentElement) {
      if (node === document.documentElement || THEME_ATTRIBUTES.some((name) => node?.hasAttribute(name))) {
        observer.observe(node, { attributes: true, attributeFilter: THEME_ATTRIBUTES });
      }
    }
    if (!element) observer.observe(document.documentElement, { attributes: true, attributeFilter: THEME_ATTRIBUTES });
  }
  return () => {
    for (const query of queries) query.removeEventListener?.("change", onChange);
    observer?.disconnect();
  };
}

/** The tract's colours for wherever `ref` sits, kept in step with the theme —
 *  including a switch made in Settings while the panel is open. */
function useTractPalette(ref: RefObject<Element | null>): Palette {
  const [palette, setPalette] = useState<Palette>(FALLBACK_PALETTE);
  useEffect(() => {
    const read = () =>
      setPalette((current) => {
        const next = readPalette(ref.current);
        return samePalette(current, next) ? current : next;
      });
    read();
    return watchTheme(ref.current, read);
  }, [ref]);
  return palette;
}

/** Reduced motion, from the system or from Settings → Motion. */
function readReducedMotion(): boolean {
  if (typeof document === "undefined") return false;
  if (document.documentElement.getAttribute("data-motion") === "reduce") return true;
  try {
    return typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  } catch {
    return false;
  }
}

function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(readReducedMotion);
  useEffect(() => watchTheme(null, () => setReduced(readReducedMotion())), []);
  return reduced;
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
  /** Name the parts (lips, alveolar ridge, soft palate…) on the drawing.
   *  Off by default: in a small panel the shapes say more than the words. */
  labels?: boolean;
}

export function VocalTract({ track, previewTime, height, label, view = VIEW_MOUTH, labels = false }: Props) {
  const player = usePlayer();
  const boxRef = useRef<HTMLDivElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const viewRef = useRef<TractView | null>(null);
  const [webgl] = useState(hasWebGL);
  const [ready, setReady] = useState(false);
  const palette = useTractPalette(boxRef);
  const still = useReducedMotion();

  // Built once. The palette is applied afterwards through setPalette(), so a
  // theme switch never costs a rebuilt scene.
  useEffect(() => {
    if (!webgl) return;
    const canvas = canvasRef.current;
    if (!canvas) return;
    let disposed = false;
    // The renderer and its shaders come in with three itself, in the chunk
    // that is only fetched when a mouth is first put on screen.
    import("./scene")
      .then(({ createTractView }) => createTractView(canvas, readPalette(boxRef.current), view))
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
      still,
    });
  }, [clock, playing, previewTime, track, still]);

  useEffect(() => {
    if (!ready) return;
    viewRef.current?.setPalette(palette);
    renderOnce();
    // renderOnce is deliberately left out: a new frame on every playback
    // change is the loop's job, this one only answers a theme switch.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [palette, ready]);

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
      {labels && <TractLabels view={view} boxRef={boxRef} />}
    </div>
  );
}

/** The names of the parts, laid over the drawing. They sit on the fixed
 *  anatomy (and low in the tongue's body, which is tongue in every pose), so
 *  they never need to move with the articulators. */
function TractLabels({ view, boxRef }: { view: ViewBox; boxRef: RefObject<HTMLDivElement | null> }) {
  const [size, setSize] = useState<{ width: number; height: number } | null>(null);
  useEffect(() => {
    const box = boxRef.current;
    if (!box) return;
    const measure = () => setSize({ width: box.clientWidth, height: box.clientHeight });
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(box);
    return () => observer.disconnect();
  }, [boxRef]);
  if (!size || size.width === 0 || size.height === 0) return null;
  // The same framing as the camera and the SVG viewBox: the whole view fits
  // ("meet"), centred, and the spare room goes to the sides.
  const scale = Math.min(size.width / view.width, size.height / view.height);
  const place = ([x, y]: Vec2) => ({
    left: size.width / 2 + (x - view.x) * scale,
    top: size.height / 2 - (y - view.y) * scale,
  });
  return (
    <div className="tract__labels" aria-hidden="true">
      {LABELS.map((entry) => {
        const { left, top } = place(entry.at);
        if (left < 0 || top < 0 || left > size.width || top > size.height) return null;
        return (
          <span
            key={entry.id}
            className={`tract__label tract__label--${entry.side}`}
            style={{ left, top }}
          >
            <span className="tract__label-text">{entry.text}</span>
          </span>
        );
      })}
    </div>
  );
}

/** SVG y counts downwards and the anatomy counts it upwards. */
const svgPath = (points: readonly Vec2[], close = true): string =>
  `M${points.map(([x, y]) => `${x.toFixed(2)},${(100 - y).toFixed(2)}`).join("L")}${close ? "Z" : ""}`;

/** Same geometry, drawn as plain SVG: without WebGL the mouth still moves,
 *  just without the light, the airflow or the highlights. It is also what the
 *  tests see, and what the lab page shows when it is asked to check this path. */
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
  const svgRef = useRef<SVGSVGElement | null>(null);
  const palette = useTractPalette(svgRef);
  // 25 frames per second is plenty for a fallback, and it keeps the DOM churn
  // an order of magnitude below the animation loop above.
  const tick = useTimeSelector(player.clock, (time) => Math.round(time * 25));
  const time = player.playing ? tick / 25 : (previewTime ?? tick / 25);
  const pose: Pose = track.keys.length > 0 ? poseAt(track, time) : REST_POSE;
  const shapes = buildTract(pose);
  const anatomy = staticOutlines();
  const tooth = lowerTooth(shapes.lowerTeeth);

  const box = `${view.x - view.width / 2} ${100 - view.y - view.height / 2} ${view.width} ${view.height}`;
  const line = palette.line;
  // Strokes are in pixels, whatever the size of the drawing.
  const stroke = (width: number, opacity = 1) => ({
    stroke: line,
    strokeWidth: width,
    strokeOpacity: opacity,
    strokeLinejoin: "round" as const,
    vectorEffect: "non-scaling-stroke" as const,
  });
  const bone = { fill: palette.marrow, ...stroke(0.8, 0.5) };

  return (
    <svg ref={svgRef} viewBox={box} className="tract__svg" aria-hidden="true">
      <path d={svgPath(shapes.airway)} fill={palette.cavity} />
      {anatomy.conchae.map((concha, index) => (
        <path key={index} d={svgPath(concha)} fill={palette.concha} {...stroke(0.8, 0.3)} />
      ))}
      <path d={svgPath(anatomy.head)} fill={palette.tissue} {...stroke(1.2, 0.75)} />
      {anatomy.vertebrae.map((vertebra, index) => (
        <path key={index} d={svgPath(vertebra)} {...bone} />
      ))}
      <path d={svgPath(anatomy.nasalBone)} {...bone} />
      <path d={svgPath(anatomy.maxilla)} {...bone} />
      <path d={svgPath(anatomy.upperTooth.outline)} fill={palette.dentine} {...stroke(0.8, 0.5)} />
      <path d={svgPath(anatomy.upperTooth.crown)} fill={palette.bone} {...stroke(1, 0.5)} />
      <path d={svgPath(closed(shapes.jawTissue))} fill={palette.tissue} {...stroke(1.2, 0.75)} />
      <path d={svgPath(closed(shapes.thyroid, 3))} fill={palette.cartilage} {...stroke(0.8, 0.5)} />
      <path d={svgPath(closed(shapes.hyoid, 3))} {...bone} />
      <path d={svgPath(closed(shapes.mandible))} {...bone} />
      <path d={svgPath(tooth.outline)} fill={palette.dentine} {...stroke(0.8, 0.5)} />
      <path d={svgPath(tooth.crown)} fill={palette.bone} {...stroke(1, 0.5)} />
      <path d={svgPath(anatomy.folds)} fill={palette.lip} {...stroke(0.8, 0.5)} />
      <path d={svgPath(shapes.epiglottis)} fill={palette.cartilage} {...stroke(1.1, 0.75)} />
      <path d={svgPath(shapes.velum)} fill={mix(palette.lip, palette.tissue, 0.3)} />
      <path d={svgPath(shapes.velum, false)} fill="none" {...stroke(1.5, 0.9)} />
      <path d={svgPath(shapes.tongue)} fill={palette.tongue} data-testid="tract-tongue" />
      <path d={svgPath(shapes.tongue, false)} fill="none" {...stroke(1.7, 0.9)} />
      <path d={`${svgPath(shapes.upperLip)} ${svgPath(shapes.lowerLip)}`} fill={mix(palette.lip, palette.tissue, 0.45)} />
      <path d={`${svgPath(shapes.upperVermilion)} ${svgPath(shapes.lowerVermilion)}`} fill={palette.lip} />
      <path d={svgPath(shapes.upperLip.slice(0, LIP_EXPOSED_POINTS), false)} fill="none" {...stroke(1.5, 0.9)} />
      <path d={svgPath(shapes.lowerLip.slice(0, LIP_EXPOSED_POINTS), false)} fill="none" {...stroke(1.5, 0.9)} />
    </svg>
  );
}
