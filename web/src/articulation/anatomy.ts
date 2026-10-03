/** The head, in midsagittal section: the fixed anatomy the tongue moves inside.
 *
 *  Everything lives in one abstract 100 × 100 space, seen from the left with
 *  the face looking to the **right** (+x = towards the lips, +y = upwards).
 *  One unit ≈ 2 mm on an adult vocal tract, which is what fixes the
 *  proportions: the distance from the upper incisors to the glottis, the height
 *  of the palatal vault, how far the jaw drops. They are not free decorations —
 *  the tongue is placed by casting rays against these outlines, so moving a
 *  point here moves where /t/ or /k/ make contact.
 *
 *  Only what does NOT move lives here. Tongue, jaw, lips and velum are
 *  computed per frame in `tract.ts` out of these landmarks.
 */

export type Vec2 = readonly [number, number];

/** Centre the tongue body is cast from (inside the jaw, below the mouth floor).
 *  Every direction of the tongue surface is an angle measured from here. */
export const TONGUE_CENTER: Vec2 = [60, 26];

/**
 * Roof of the tract, from the bottom of the pharynx to the upper incisors.
 *
 * This is the wall the tongue is clipped against: a phone with maximum raising
 * touches it exactly, and one with less leaves a proportional gap. The list
 * ends AT the incisors on purpose — beyond them there is no wall, only the gap
 * between the teeth, which is what lets the tip come out for /θ/ and /ð/.
 */
export const TRACT_WALL: readonly Vec2[] = [
  [50, 6],     // larynx, just above the glottis
  [47.5, 14],
  [45.8, 24],  // back wall of the pharynx
  [45.5, 34],
  [46, 42],    // where the raised velum seals
  [49, 45.5],
  [53, 47.2],  // rear edge of the hard palate
  [57.5, 48.4],
  [62, 48.6],  // palatal vault
  [67, 47.6],
  [70.5, 46.4],
  [73, 45.2],  // alveolar ridge
  [76, 44],
  [79, 43.5],  // root of the upper incisors
  [80.5, 39],  // tip of the upper incisors
];

/**
 * Lowest position the tongue surface can take: the floor of the mouth and, at
 * the back, a fully advanced tongue root (a wide pharynx).
 *
 * Raising is read between this curve and `TRACT_WALL`, so 0 is a tongue lying
 * flat on the floor and 1 is contact. An open /ɑ/ lives near the bottom of that
 * range and a /k/ at the very top of it.
 */
export const TONGUE_FLOOR: readonly Vec2[] = [
  [53, 19],    // fully advanced root: the pharynx at its widest
  [54.5, 22],
  [57, 28],
  [61, 32],
  [66, 33.4],
  [70.5, 34],
  [74, 34.2],  // resting tip, behind the lower incisors
];

/** Hard palate + alveolar ridge, as a drawn surface (the part of the wall that
 *  is bone: it gets the "teeth" colour, the velum behind it does not). */
export const HARD_PALATE: readonly Vec2[] = TRACT_WALL.slice(6, 15);

/** Back wall of the pharynx, from the velar seal down to the larynx. */
export const PHARYNX_WALL: readonly Vec2[] = TRACT_WALL.slice(0, 6);

/** Where the velum hinges off the hard palate. */
export const VELUM_ROOT: Vec2 = [53.4, 47.1];
/** Velum raised: sealed against the pharyngeal wall, nasal port shut. */
export const VELUM_CLOSED_TIP: Vec2 = [46.6, 42.6];
/** Velum lowered: hanging into the pharynx, air free to reach the nose. */
export const VELUM_OPEN_TIP: Vec2 = [50.6, 37.2];

/** Nasal cavity: above the palate, from the velar port out to the nostril. */
export const NASAL_CAVITY: readonly Vec2[] = [
  [47.5, 44],
  [50, 52],
  [56, 56],
  [64, 57.5],
  [72, 57],
  [80, 55],
  [87, 52.5],
  [90.5, 50.5],  // nostril
  [90, 48.5],
  [84, 49.5],
  [76, 51],
  [68, 52.5],
  [61, 52.5],
  [55.5, 51.5],
  [51, 49],
  [49.5, 44.5],
];

/** Upper incisors: they hang off the alveolar process and never move. */
export const UPPER_TEETH: readonly Vec2[] = [
  [76.5, 44.6],
  [80.2, 43.8],
  [80.6, 38.6],
  [78.6, 38.4],
  [77.6, 42.5],
];

/** Lower incisors, in the jaw's own frame (before rotation). */
export const LOWER_TEETH: readonly Vec2[] = [
  [77.4, 33.4],
  [79.6, 33.2],
  [80.2, 38.2],
  [78.2, 38.4],
  [77.6, 35.6],
];

/** The jaw turns around the condyle, just in front of the ear. */
export const JAW_PIVOT: Vec2 = [42, 50];
/** Widest opening we draw, in radians. Speech uses ~10–12°, not a yawn. */
export const JAW_MAX_ANGLE = 0.21;

/** Floor of the mouth: the underside of the tongue, carried by the jaw.
 *  It runs below `TONGUE_FLOOR` — the gap between the two is the thickness a
 *  flattened tongue still has. */
export const MOUTH_FLOOR: readonly Vec2[] = [
  [74, 31.5],
  [68, 27.5],
  [62, 24],
  [57, 20],
  [53.5, 16.5],
  [52, 15],
];

/** The mandible, cut at the chin: a section, not the whole jaw seen sideways.
 *  It fills the chin and tells the lower face apart from the tongue above it. */
export const MANDIBLE_SECTION: readonly Vec2[] = [
  [77.5, 30.5],
  [82, 30.5],
  [84.5, 28],
  [84.5, 24.5],
  [82, 22.5],
  [78.5, 23],
  [76.5, 26],
];

/** The eye. There is no eye in a midsagittal section — but without one the
 *  silhouette stops reading as a face, and a face is what tells the learner at
 *  a glance which way the mouth is pointing. */
export const EYE: readonly Vec2[] = [
  [80.2, 61.2],
  [81.6, 62.6],
  [83.4, 62.8],
  [85, 61.8],
  [83.4, 60.4],
  [81.4, 60.2],
];

/** Where the lips meet when closed, at rest. Aperture opens around this line. */
export const LIP_LINE_Y = 39.2;
/** How far the lips travel forward when fully rounded. */
export const LIP_PROTRUSION = 3.4;
/** Vertical gap between the lips at maximum aperture. */
export const LIP_MAX_GAP = 12;

/** Larynx: the voicing indicator sits here, at the vocal folds. */
export const GLOTTIS: Vec2 = [51, 11.5];

/**
 * Everything above and behind the tract, as one silhouette: nose, palate,
 * skull and the back of the neck. Its lower edge IS `TRACT_WALL`, so the
 * cavity of the mouth is simply where no tissue was drawn — nothing has to
 * compute the shape of the air.
 *
 * The face is there because a tongue with no head around it is a shape
 * floating in the void; it is drawn recessive so the tract stays the subject.
 */
export const UPPER_FACE: readonly Vec2[] = [
  ...TRACT_WALL,   // larynx → pharynx → velum → palate → upper incisors
  [81.8, 43.2],    // up behind the upper lip, which is drawn separately
  [84.5, 45.4],
  [88.5, 46.4],
  [90.4, 47.4],    // philtrum
  [90.2, 48.6],    // base of the nose
  [93, 49.6],
  [95.2, 52],      // tip of the nose
  [93.6, 54.6],
  [91.4, 57.6],
  [89.6, 61.4],    // bridge
  [88.4, 64.6],
  [87.2, 67],      // brow
  [85.5, 72],
  [83, 80],
  [76, 88],
  [64, 92],
  [50, 91],
  [37, 84],
  [28, 72],
  [25, 58],
  [27, 44],
  [30, 30],
  [32, 18],
  [34, 0],         // back of the neck
  [52, 0],         // front of the neck, under the larynx
];

/**
 * Tissue of the lower jaw, in its own frame: chin, the floor of the mouth and
 * the skin under the jaw. It rotates with the mandible, so the outline of the
 * head stays closed when the mouth opens.
 */
export const LOWER_FACE: readonly Vec2[] = [
  [84.2, 35.4],    // behind the lower lip
  [74, 31.5],      // floor of the mouth: the same curve the tongue sits on
  [68, 27.5],
  [62, 24],
  [57, 19.5],
  [52.5, 14],
  [50.5, 10],
  [50.5, 2],
  [62, 4],
  [74, 12],
  [82, 18],
  [86.2, 23],      // point of the chin
  [88, 33],
  [89.4, 36.2],
];

export interface ViewBox {
  /** Centre of the framing. */
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Framing for a panel: nose, lips, chin, eye and the whole tract, with the
 *  top of the skull cropped away. In a box wider than it is tall — which the
 *  side panel is — framing the entire head would leave the mouth, the part
 *  anyone is actually looking at, half the size it could be. */
export const VIEW_MOUTH: ViewBox = { x: 71, y: 38, width: 60, height: 60 };

/** The whole profile, for anywhere there is room for it (the lab page). */
export const VIEW_HEAD: ViewBox = { x: 61, y: 46, width: 82, height: 84 };

/** Linear interpolation, the workhorse of every pose blend here. */
export const lerp = (a: number, b: number, t: number): number => a + (b - a) * t;

export const lerpPoint = (a: Vec2, b: Vec2, t: number): Vec2 => [
  lerp(a[0], b[0], t),
  lerp(a[1], b[1], t),
];

/**
 * First crossing of the ray `origin + t·dir` (t > 0) with a polyline.
 *
 * This is what ties the tongue to the anatomy: every surface sample is cast
 * from `TONGUE_CENTER` and stopped at the wall, so "fully raised" means
 * "touching the roof exactly here" and not a number that happens to look right.
 * `null` when the ray escapes the outline (in front of the incisors there is no
 * wall, and that is what lets the tip come out between the teeth).
 */
export function rayHit(origin: Vec2, angle: number, path: readonly Vec2[]): number | null {
  const dx = Math.cos(angle);
  const dy = Math.sin(angle);
  let best: number | null = null;
  for (let i = 0; i < path.length - 1; i += 1) {
    const [ax, ay] = path[i];
    const [bx, by] = path[i + 1];
    const ex = bx - ax;
    const ey = by - ay;
    const denominator = dx * ey - dy * ex;
    if (Math.abs(denominator) < 1e-9) continue; // parallel
    const ox = ax - origin[0];
    const oy = ay - origin[1];
    const t = (ox * ey - oy * ex) / denominator;     // along the ray
    const u = (ox * dy - oy * dx) / denominator;     // along the segment
    if (t <= 0 || u < 0 || u > 1) continue;
    if (best === null || t < best) best = t;
  }
  return best;
}

/** Rotate a point around a pivot (the jaw, and everything it carries). */
export function rotateAround(point: Vec2, pivot: Vec2, angle: number): Vec2 {
  const cos = Math.cos(angle);
  const sin = Math.sin(angle);
  const dx = point[0] - pivot[0];
  const dy = point[1] - pivot[1];
  return [pivot[0] + dx * cos - dy * sin, pivot[1] + dx * sin + dy * cos];
}

/**
 * Catmull-Rom through the control points, sampled to `count` points.
 *
 * The outlines above are landmarks, not drawings: a straight-line join makes
 * the palate look like a folded sheet. The spline passes through every one of
 * them, so the landmarks stay exact.
 */
export function smoothPath(points: readonly Vec2[], count: number): Vec2[] {
  if (points.length < 2) return points.map((p) => [p[0], p[1]]);
  const out: Vec2[] = [];
  const segments = points.length - 1;
  for (let i = 0; i < count; i += 1) {
    const position = (i / (count - 1)) * segments;
    const index = Math.min(Math.floor(position), segments - 1);
    const t = position - index;
    const p0 = points[Math.max(index - 1, 0)];
    const p1 = points[index];
    const p2 = points[index + 1];
    const p3 = points[Math.min(index + 2, points.length - 1)];
    const t2 = t * t;
    const t3 = t2 * t;
    const at = (a: number, b: number, c: number, d: number) =>
      0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3);
    out.push([at(p0[0], p1[0], p2[0], p3[0]), at(p0[1], p1[1], p2[1], p3[1])]);
  }
  return out;
}
