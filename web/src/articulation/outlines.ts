/** The anatomy's landmarks turned into the smooth outlines both renderers
 *  draw — the WebGL scene and the SVG fallback — so the two never disagree
 *  about the shape of a tooth. Pure geometry; no three.js here. */

import {
  CONCHAE,
  HEAD,
  LOWER_CROWN,
  MAXILLA,
  NASAL_BONE,
  UPPER_CROWN,
  UPPER_INCISOR,
  VERTEBRAE,
  VOCAL_FOLDS,
  spline,
  type Vec2,
} from "./anatomy";

/** Samples per landmark on the smooth outlines. */
export const SMOOTH = 4;

export const closed = (points: readonly Vec2[], per = SMOOTH): Vec2[] => spline(points, per, true);

/** A tooth outline smoothed, and its crown cut off along the cemento-enamel
 *  junction: the crown is enamel, the rest is root inside the bone. */
export function toothParts(
  points: readonly Vec2[],
  crown: { from: number; to: number },
): { outline: Vec2[]; crown: Vec2[] } {
  const outline = closed(points, SMOOTH);
  const n = outline.length;
  const part: Vec2[] = [];
  for (let i = crown.from * SMOOTH; ; i += 1) {
    part.push(outline[i % n]);
    if (i % n === crown.to * SMOOTH) break;
  }
  return { outline, crown: part };
}

export const lowerTooth = (points: readonly Vec2[]) => toothParts(points, LOWER_CROWN);

export interface StaticOutlines {
  head: Vec2[];
  maxilla: Vec2[];
  nasalBone: Vec2[];
  vertebrae: Vec2[][];
  conchae: Vec2[][];
  folds: Vec2[];
  upperTooth: { outline: Vec2[]; crown: Vec2[] };
}

let cached: StaticOutlines | null = null;

/** Smoothed once, on first use. */
export function staticOutlines(): StaticOutlines {
  cached ??= {
    head: closed(HEAD),
    maxilla: closed(MAXILLA),
    nasalBone: closed(NASAL_BONE, 3),
    vertebrae: VERTEBRAE.map((vertebra) => closed(vertebra, 3)),
    conchae: CONCHAE.map((concha) => closed(concha)),
    folds: closed(VOCAL_FOLDS, 3),
    upperTooth: toothParts(UPPER_INCISOR, UPPER_CROWN),
  };
  return cached;
}
