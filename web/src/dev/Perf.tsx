/** `/articulator.html?perf=1`: how long a frame of the mouth costs.
 *
 *  Renders the word "butter" frame after frame on a canvas the size of the
 *  lesson panel and writes the averages into the page, where a headless
 *  browser can read them. Geometry (pure JS) is timed apart from the whole
 *  frame, which also includes the WebGL draw. Development only.
 */

import { useEffect, useRef, useState } from "react";

import { readPalette } from "../articulation/palette";
import { createTractView } from "../articulation/scene";
import { buildTract } from "../articulation/tract";
import { buildTrack, poseAt, type TimedPhone } from "../articulation/track";

export function Perf() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [result, setResult] = useState("running");

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    let cancelled = false;
    void (async () => {
      const view = await createTractView(canvas, readPalette(document.body));
      if (!view || cancelled) return setResult("no webgl");
      view.setSize(480, 300);
      const timed: TimedPhone[] = ["b", "ʌ", "ɾ", "ɚ"].map((symbol, i) => [symbol, 0.2 + i * 0.16, 0.22 + i * 0.16]);
      const track = buildTrack(timed);
      const frames = 400;
      const at = (i: number) => track.start + ((track.end - track.start) * (i % 100)) / 100;
      // Warm up, then time.
      for (let i = 0; i < 30; i += 1) view.render({ pose: poseAt(track, at(i)), manner: null, time: i / 60, active: true });
      let t0 = performance.now();
      for (let i = 0; i < frames; i += 1) buildTract(poseAt(track, at(i)));
      const geometry = (performance.now() - t0) / frames;
      t0 = performance.now();
      for (let i = 0; i < frames; i += 1) {
        view.render({ pose: poseAt(track, at(i)), manner: "stop", time: i / 60, active: true });
      }
      const frame = (performance.now() - t0) / frames;
      view.dispose();
      setResult(`geometry=${geometry.toFixed(3)}ms frame=${frame.toFixed(3)}ms`);
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div style={{ padding: 12 }}>
      <canvas ref={canvasRef} width={480} height={300} style={{ width: 480, height: 300 }} />
      <p id="perf-result">{result}</p>
    </div>
  );
}
