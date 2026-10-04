/** From a pose to the outlines that get drawn: pure geometry, no renderer.
 *
 *  The tongue is not drawn from a stored shape per phone. Its surface is cast
 *  as rays from a centre inside the jaw and each ray is stopped somewhere
 *  between the floor of the mouth and the roof of the tract, so "raising = 1"
 *  IS contact with the roof at that point. That is what makes /t/ touch the
 *  alveolar ridge and /k/ the soft palate without either being drawn by hand,
 *  and it is why the tongue can never pass through the palate.
 *
 *  Every list returned has a fixed length, so the renderer can allocate its
 *  buffers once and only rewrite coordinates.
 */

import {
  EPIGLOTTIS,
  EPIGLOTTIS_LINGUAL,
  FRENULUM,
  GENIOGLOSSUS_ORIGIN,
  HYOID,
  JAW_MAX_ANGLE,
  JAW_PIVOT,
  LIP_LINE_Y,
  LIP_MAX_GAP,
  LIP_PROTRUSION,
  LOWER_FACE,
  LOWER_TEETH,
  MANDIBLE,
  MOUTH_FLOOR,
  THYROID,
  TONGUE_CENTER,
  TONGUE_FLOOR,
  TRACT_ROOF,
  VELUM_LOWERED,
  VELUM_RAISED,
  VELUM_UVULA,
  lerp,
  lerpPoint,
  rayHit,
  resample,
  rotateAround,
  smoothPath,
  spline,
  type Vec2,
} from "./anatomy";
import type { Pose } from "./pose";

const DEG = Math.PI / 180;

/** Where the tongue surface starts: pointing down and back, at the root. */
const ROOT_ANGLE = 232 * DEG;
/** Direction of a palatal constriction (/i/, /j/) seen from the tongue centre. */
const BODY_FRONT_ANGLE = 84 * DEG;
/** Direction of an upper-pharyngeal constriction (/ɑ/). Between the two lies
 *  the whole front↔back vowel axis, which is what `pose.body` selects. */
const BODY_BACK_ANGLE = 140 * DEG;
/** How wide the tongue-body hump is. Narrower and the tongue looks pinched;
 *  wider and /i/ and /u/ stop being distinguishable. */
const BODY_SPREAD = 30 * DEG;
/** The pharyngeal stretch that the tongue root narrows. */
const ROOT_CENTER = 186 * DEG;
const ROOT_SPREAD = 32 * DEG;
/** Muscle tone: even a "flat" tongue is not lying on the floor of the mouth. */
const BASE_RAISE = 0.12;

/** Rays used for the tongue body, plus the blade and tip after them. */
const BODY_RAYS = 26;
/** Points on the final surface curve (body + blade + tip). */
export const TONGUE_SURFACE_POINTS = 46;
/** How far clear of the tongue the lowered velum stays. */
const VELUM_CLEARANCE = 0.15;
/** Points on the underside, which closes the tongue polygon. It matches the
 *  surface so the renderer can pair them into one strip of fixed topology. */
export const TONGUE_UNDERSIDE_POINTS = TONGUE_SURFACE_POINTS;

/** Where the tip goes when it is fully raised, by target. Interpolating
 *  between these three is what `pose.tipFront` means. */
const TIP_POSTALVEOLAR: Vec2 = [68.5, 47.8];
const TIP_ALVEOLAR: Vec2 = [73, 45.2];
const TIP_INTERDENTAL: Vec2 = [82.5, 38.6];
/** Tip at rest: on the floor of the mouth, behind the lower incisors. */
const TIP_REST: Vec2 = [74, 34];

/** Front of the mouth follows the jaw completely, the root not at all: the
 *  tongue hangs off the mandible at the front and off the hyoid at the back.
 *  Rotating all of it rigidly drove the root through the pharyngeal wall. */
const JAW_FOLLOW_BACK = 48;
const JAW_FOLLOW_FRONT = 68;

const gaussian = (x: number, center: number, spread: number): number =>
  Math.exp(-((x - center) ** 2) / (2 * spread * spread));

const clamp01 = (value: number): number => (value < 0 ? 0 : value > 1 ? 1 : value);

export interface Constriction {
  /** Where the tract is narrowest, in the same 100 × 100 space. */
  point: Vec2;
  /** Free distance left, in units (≈ 2 mm each). 0 is a closure. */
  gap: number;
  /** Which articulator makes it: this is what the label reads off. */
  where: "lips" | "teeth" | "tongue";
}

export interface TractShapes {
  /** Closed polygon: surface from the root to the tip, then the underside. */
  tongue: Vec2[];
  /** Upper surface only, from root to tip (the stroke, and the airflow path). */
  tongueSurface: Vec2[];
  /** Underside, tip to root: paired with the surface it is one strip of fixed
   *  topology, which is what the renderer draws without reallocating. */
  tongueUnderside: Vec2[];
  /** Closed outline; its first `LIP_EXPOSED_POINTS` are the free surface
   *  (skin, vermilion, the inside of the lip), the rest is where it grows out
   *  of the face and is neither stroked nor shaded. */
  upperLip: Vec2[];
  lowerLip: Vec2[];
  /** The red of each lip round its free edge: its first
   *  `VERMILION_EXPOSED_POINTS` lie on the lip's outline. */
  upperVermilion: Vec2[];
  lowerVermilion: Vec2[];
  /** Lower incisor, crown and root, carried by the jaw. */
  lowerTeeth: Vec2[];
  /** Chin, floor of the mouth, larynx and neck, already moved with the jaw. */
  jawTissue: Vec2[];
  /** The mandible in section, carried by the jaw like everything else on it. */
  mandible: Vec2[];
  /** Closed outline, oral side (root → uvula) then nasal side. */
  velum: Vec2[];
  epiglottis: Vec2[];
  hyoid: Vec2[];
  thyroid: Vec2[];
  /** Where the genioglossus fans out from, moved with the jaw. */
  muscleOrigin: Vec2;
  /** The air inside the head: mouth, pharynx, larynx and nose, bounded by
   *  whatever the articulators leave open. Drawn first; tissue covers it. */
  airway: Vec2[];
  /** Vertical distance between the lips, in units. */
  lipGap: number;
  /** The tightest point of the tract: the highlight, and where noise is born. */
  constriction: Constriction;
  /** For a tongue constriction, the stretch of the roof it is made against —
   *  the passive articulator, which is what "place of articulation" names. */
  place: Vec2[];
}

/** Jaw rotation for a pose. Negative because opening swings the chin down. */
export const jawAngle = (pose: Pose): number => -pose.jaw * JAW_MAX_ANGLE;

const applyJaw = (points: readonly Vec2[], angle: number): Vec2[] =>
  points.map((point) => rotateAround(point, JAW_PIVOT, angle));

const jawFollow = (x: number): number =>
  clamp01((x - JAW_FOLLOW_BACK) / (JAW_FOLLOW_FRONT - JAW_FOLLOW_BACK));

const applyJawGraded = (points: readonly Vec2[], angle: number): Vec2[] =>
  points.map((point) => rotateAround(point, JAW_PIVOT, angle * jawFollow(point[0])));

/** The neck does not swing with the chin: below the hyoid the weight fades
 *  out, so opening the mouth stretches the skin under the jaw instead of
 *  tearing the outline of the throat. */
const applyJawNeck = (points: readonly Vec2[], angle: number): Vec2[] =>
  points.map((point) =>
    rotateAround(point, JAW_PIVOT, angle * jawFollow(point[0]) * clamp01((point[1] - 4) / 12)),
  );

/**
 * How much the tongue reaches towards the roof along one direction.
 *
 * Three contributions add up: the body hump (placed by `body`, sized by
 * `height`), the root pushing back into the pharynx, and the blade being
 * dragged along by a raised tip. Anything above 1 would go through the palate,
 * so it is clipped there.
 */
export function raiseAt(angle: number, pose: Pose, apexAngle: number): number {
  const bodyCenter = lerp(BODY_BACK_ANGLE, BODY_FRONT_ANGLE, pose.body);
  const body = pose.height * gaussian(angle, bodyCenter, BODY_SPREAD);
  const root = 0.6 * pose.root * gaussian(angle, ROOT_CENTER, ROOT_SPREAD);
  // The blade rises with the tip and carries on right up to it: a blade that
  // fell away just behind the apex drew the tip as a spike on a step.
  const blade = 0.6 * pose.tip * gaussian(angle, apexAngle + 9 * DEG, 17 * DEG);
  // Tone fills in where nothing else is happening, but must not add to a peak:
  // a vowel asking for 0.9 would otherwise end up in contact with the palate.
  const shape = clamp01(body + root + blade);
  return shape + BASE_RAISE * (1 - shape);
}

/** Direction of the tip target, needed before the body rays are cast: the
 *  blade has to stop behind the tip, wherever the tip is aiming. */
function apexPoint(pose: Pose, center: Vec2, jaw: number): Vec2 {
  const target =
    pose.tipFront <= 0.5
      ? lerpPoint(TIP_POSTALVEOLAR, TIP_ALVEOLAR, pose.tipFront * 2)
      : lerpPoint(TIP_ALVEOLAR, TIP_INTERDENTAL, (pose.tipFront - 0.5) * 2);
  const rest = rotateAround(TIP_REST, JAW_PIVOT, jaw);
  const apex = lerpPoint(rest, target, clamp01(pose.tip));
  // Keep it in front of the centre: behind it the surface would fold over.
  return [Math.max(apex[0], center[0] + 6), apex[1]];
}

/** The tongue surface, from the root round to the tip. */
function tongueSurface(
  pose: Pose,
  center: Vec2,
  floor: readonly Vec2[],
  jaw: number,
  roof: readonly Vec2[],
): Vec2[] {
  const apex = apexPoint(pose, center, jaw);
  const apexAngle = Math.atan2(apex[1] - center[1], apex[0] - center[0]);
  // The last body ray sits just behind the tip, so the surface keeps moving
  // forward and never doubles back on itself.
  const lastRay = Math.max(apexAngle + 9 * DEG, 45 * DEG);

  const controls: Vec2[] = [];
  for (let i = 0; i < BODY_RAYS; i += 1) {
    const angle = ROOT_ANGLE + ((lastRay - ROOT_ANGLE) * i) / (BODY_RAYS - 1);
    const low = rayHit(center, angle, floor);
    const wall = rayHit(center, angle, roof);
    // No floor under this ray (it escapes forwards): fall back to the wall so
    // the surface stays continuous instead of collapsing onto the centre.
    const inner = low ?? (wall !== null ? wall * 0.55 : 12);
    const outer = wall ?? inner + 14;
    const radius = inner + raiseAt(angle, pose, apexAngle) * Math.max(outer - inner, 0);
    controls.push([center[0] + Math.cos(angle) * radius, center[1] + Math.sin(angle) * radius]);
  }
  controls.push(apex);

  // Smoothing runs between the sampled rays, and a spline can bulge past the
  // point it was fitted to. Push anything that ended up beyond the roof back
  // onto it: no shape the tongue takes may cross the palate. Past the incisors
  // `rayHit` finds no wall, which is exactly where the tip is allowed out.
  return smoothPath(controls, TONGUE_SURFACE_POINTS).map((point): Vec2 => {
    const angle = Math.atan2(point[1] - center[1], point[0] - center[0]);
    const wall = rayHit(center, angle, roof);
    if (wall === null) return point;
    const radius = Math.hypot(point[0] - center[0], point[1] - center[1]);
    if (radius <= wall) return point;
    return [center[0] + Math.cos(angle) * wall, center[1] + Math.sin(angle) * wall];
  });
}

/**
 * The underside, from the tip back to the root.
 *
 * The tip is rounded, not a point, and the front of the tongue bulges
 * forwards on its way down to the frenulum, so a raised tip leaves the
 * sublingual pocket open beneath it, which is what a /t/ or an /l/ looks like
 * from the side. Behind the frenulum the tongue is one with the floor of the
 * mouth. Nothing of it may sink into the lower incisor: for /θ/ the tongue
 * lies over the incisal edge.
 */
function tongueUnderside(pose: Pose, apex: Vec2, jaw: number, lowerTeeth: readonly Vec2[]): Vec2[] {
  const frenulum = rotateAround(FRENULUM, JAW_PIVOT, jaw * jawFollow(FRENULUM[0]));
  const chord: Vec2 = [frenulum[0] - apex[0], frenulum[1] - apex[1]];
  const length = Math.hypot(chord[0], chord[1]) || 1;
  // Perpendicular to the chord, pointing away from the body of the tongue.
  const forward: Vec2 = [-chord[1] / length, chord[0] / length];
  // How far the front bulges: more when the tip is up and the front is long.
  const bulge = Math.min(1.9, 0.25 + length * 0.11) * (0.5 + 0.5 * clamp01(pose.tip));
  const at = (s: number, b: number): Vec2 => [
    apex[0] + chord[0] * s + forward[0] * b,
    apex[1] + chord[1] * s + forward[1] * b,
  ];
  const controls: Vec2[] = [
    apex,
    at(0.05, bulge * 0.55),
    at(0.2, bulge * 0.95),
    at(0.45, bulge),
    at(0.75, bulge * 0.6),
    frenulum,
    ...applyJawGraded(MOUTH_FLOOR.slice(1), jaw),
  ];
  const edge = lowerTeeth[0][1];
  // The lingual face of the incisor, crown to root, as a function of height.
  const lingual = lowerTeeth.slice(7).sort((a, b) => a[1] - b[1]);
  const lingualX = (y: number): number => {
    for (let i = 0; i < lingual.length - 1; i += 1) {
      const [ax, ay] = lingual[i];
      const [bx, by] = lingual[i + 1];
      if (y >= ay && y <= by) return lerp(ax, bx, (y - ay) / (by - ay || 1));
    }
    return y < lingual[0][1] ? lingual[0][0] : lingual[lingual.length - 1][0];
  };
  return resample(spline(controls, 6), TONGUE_UNDERSIDE_POINTS).map((point, index): Vec2 => {
    if (index === 0 || !insidePolygon(point, lowerTeeth)) return point;
    // Near the edge it rests on top of the tooth; lower down it stays behind it.
    return point[1] > edge - 2.5 ? [point[0], edge + 0.35] : [lingualX(point[1]) - 0.3, point[1]];
  });
}

/** Even-odd test: is the point inside the closed outline? */
function insidePolygon([x, y]: Vec2, polygon: readonly Vec2[]): boolean {
  let hit = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i, i += 1) {
    const [xi, yi] = polygon[i];
    const [xj, yj] = polygon[j];
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) hit = !hit;
  }
  return hit;
}

/** Distance from a point to the roof, measured along the ray that produced it. */
function gapToWall(point: Vec2, center: Vec2, roof: readonly Vec2[]): number {
  const angle = Math.atan2(point[1] - center[1], point[0] - center[0]);
  const wall = rayHit(center, angle, roof);
  if (wall === null) return Infinity; // past the incisors there is no roof
  const radius = Math.hypot(point[0] - center[0], point[1] - center[1]);
  return wall - radius;
}

interface LipPoint {
  point: Vec2;
  /** How much it follows the aperture (the free edge follows it fully). */
  open: number;
  /** How much it follows the protrusion of rounding. */
  round: number;
}

interface LipShape {
  points: readonly LipPoint[];
  /** Behind the lip, inside the face: closes the outline, never seen. */
  hidden: readonly Vec2[];
  /** Index of the free edge, where the two lips meet. */
  free: number;
  /** The vermilion runs between these two landmarks. */
  vermilion: readonly [number, number];
}

/** Upper lip, from the subnasale (where it leaves the face) down the skin of
 *  the philtrum, which slants forwards, over the everted vermilion, round the
 *  free edge and up its inside, resting on the incisor, to the gum. */
const UPPER_LIP: LipShape = {
  points: [
    { point: [90.4, 48.6], open: 0, round: 0 },        // subnasale: anchored on the face
    { point: [90.95, 46.8], open: 0.06, round: 0.45 }, // philtrum
    { point: [91.65, 44.6], open: 0.22, round: 0.9 },  // vermilion border
    { point: [91.95, 43.0], open: 0.42, round: 1 },    // labrale superius
    { point: [91.35, 41.3], open: 0.78, round: 1 },
    { point: [89.3, 40.4], open: 1, round: 1 },        // free edge
    { point: [85.8, 40.3], open: 0.85, round: 0.7 },
    { point: [83.0, 41.6], open: 0.5, round: 0.4 },    // resting on the incisor
    { point: [82.4, 44.6], open: 0.12, round: 0.1 },   // against the gum
  ],
  hidden: [[83.0, 47.6], [87.0, 48.7]],
  free: 5,
  vermilion: [2, 7],
};

/** Lower lip, from the labiomental fold up the skin, over the vermilion,
 *  round the free edge and down its inside to the gum. */
const LOWER_LIP: LipShape = {
  points: [
    { point: [86.0, 29.6], open: 0, round: 0 },        // labiomental fold: on the chin
    { point: [87.7, 31.0], open: 0.08, round: 0.4 },
    { point: [89.15, 33.1], open: 0.24, round: 0.85 }, // vermilion border
    { point: [90.1, 35.4], open: 0.5, round: 1 },      // labrale inferius
    { point: [89.75, 37.4], open: 0.85, round: 1 },
    { point: [88.3, 38.3], open: 1, round: 1 },        // free edge
    { point: [85.2, 38.1], open: 0.85, round: 0.7 },
    { point: [81.6, 36.8], open: 0.4, round: 0.35 },   // resting on the incisor
    { point: [81.4, 34.4], open: 0.06, round: 0.06 },  // against the gum
  ],
  hidden: [[82.4, 32.0], [84.4, 30.4]],
  free: 5,
  vermilion: [2, 7],
};

/** Where the lower lip goes for /f/ and /v/: tucked under the upper incisors. */
const LIP_TUCK_TARGET: Vec2 = [81.8, 39.5];

/** Points on each lip's free surface (stroked and shaded). */
export const LIP_EXPOSED_POINTS = 28;
const LIP_HIDDEN_POINTS = 6;
export const UPPER_LIP_POINTS = LIP_EXPOSED_POINTS + LIP_HIDDEN_POINTS;
export const LOWER_LIP_POINTS = LIP_EXPOSED_POINTS + LIP_HIDDEN_POINTS;
export const VELUM_POINTS = 32;
export const EPIGLOTTIS_POINTS = 22;
/** Points on the stretch of roof marked as the place of articulation. */
export const PLACE_POINTS = 14;

/** Points on the vermilion's free edge, and on the seam that closes it. */
export const VERMILION_EXPOSED_POINTS = 18;
const VERMILION_SEAM_POINTS = 4;
export const VERMILION_POINTS = VERMILION_EXPOSED_POINTS + VERMILION_SEAM_POINTS;
const PER = 6;

/** A lip outline: its free surface resampled evenly, then the hidden seam;
 *  and the vermilion, the red of the lip, as a band round its free edge. */
function lipOutline(
  exposed: readonly Vec2[],
  hidden: readonly Vec2[],
  [from, to]: readonly [number, number],
): { outline: Vec2[]; vermilion: Vec2[] } {
  const fine = spline(exposed, PER);
  const free = resample(fine, LIP_EXPOSED_POINTS);
  const seam = resample(
    spline([exposed[exposed.length - 1], ...hidden, exposed[0]], 4),
    LIP_HIDDEN_POINTS + 2,
  ).slice(1, -1);
  // The vermilion's inner border runs through the body of the lip, bowed
  // towards its middle.
  const all = [...exposed, ...hidden];
  const middle: Vec2 = [
    all.reduce((sum, p) => sum + p[0], 0) / all.length,
    all.reduce((sum, p) => sum + p[1], 0) / all.length,
  ];
  const a = exposed[to];
  const b = exposed[from];
  const bow = (t: number): Vec2 => {
    const on = lerpPoint(a, b, t);
    const pull = 0.42 * Math.sin(Math.PI * t);
    return lerpPoint(on, middle, pull);
  };
  const edge = resample(fine.slice(from * PER, to * PER + 1), VERMILION_EXPOSED_POINTS);
  const inner = Array.from({ length: VERMILION_SEAM_POINTS }, (_, i) => bow((i + 1) / (VERMILION_SEAM_POINTS + 1)));
  return { outline: [...free, ...seam], vermilion: [...edge, ...inner] };
}

interface Lips {
  upper: Vec2[];
  lower: Vec2[];
  upperVermilion: Vec2[];
  lowerVermilion: Vec2[];
  gap: number;
  upperEdge: Vec2;
  lowerEdge: Vec2;
}

function buildLips(pose: Pose, jaw: number): Lips {
  const gap = clamp01(pose.lipOpen) * LIP_MAX_GAP;
  const protrusion = clamp01(pose.lipRound) * LIP_PROTRUSION;
  // The lip line itself drops a little with the jaw: the whole mouth moves.
  const line = LIP_LINE_Y - pose.jaw * 2.2;

  const upperEdge = line + gap / 2;
  const upperShift = upperEdge - UPPER_LIP.points[UPPER_LIP.free].point[1];
  const upper = UPPER_LIP.points.map(({ point, open, round }): Vec2 => [
    point[0] + protrusion * round,
    point[1] + upperShift * open,
  ]);

  // The lower lip rides on the jaw, and then the lip muscles correct whatever
  // aperture the jaw left: that is how /p/ can close over an open jaw.
  const rotated = LOWER_LIP.points.map(({ point }) => rotateAround(point, JAW_PIVOT, jaw));
  const lowerEdge = line - gap / 2;
  const lowerShift = lowerEdge - rotated[LOWER_LIP.free][1];
  const tuck = clamp01(pose.lipTuck);
  const lower = rotated.map((point, index): Vec2 => {
    const { open, round } = LOWER_LIP.points[index];
    const moved: Vec2 = [point[0] + protrusion * round, point[1] + lowerShift * open];
    if (tuck === 0) return moved;
    return lerpPoint(moved, LIP_TUCK_TARGET, tuck * open * open);
  });

  const upperLip = lipOutline(upper, UPPER_LIP.hidden, UPPER_LIP.vermilion);
  const lowerLip = lipOutline(lower, applyJaw(LOWER_LIP.hidden, jaw), LOWER_LIP.vermilion);
  return {
    upper: upperLip.outline,
    lower: lowerLip.outline,
    upperVermilion: upperLip.vermilion,
    lowerVermilion: lowerLip.vermilion,
    gap: Math.max(0, upperEdge - lowerEdge),
    upperEdge: upper[UPPER_LIP.free],
    lowerEdge: lower[LOWER_LIP.free],
  };
}

/** Top of the tongue directly under `x` — where its surface crosses that
 *  vertical — or -Infinity if it does not reach it. */
function tongueTopAt(surface: readonly Vec2[], x: number): number {
  let top = -Infinity;
  for (let i = 0; i < surface.length - 1; i += 1) {
    const [ax, ay] = surface[i];
    const [bx, by] = surface[i + 1];
    if ((x < ax && x < bx) || (x > ax && x > bx)) continue;
    const y = ax === bx ? Math.max(ay, by) : lerp(ay, by, (x - ax) / (bx - ax));
    if (y > top) top = y;
  }
  return top;
}

/** Back of the tongue at height `y`, or +Infinity if the root is not there. */
function tongueBackAt(surface: readonly Vec2[], y: number): number {
  let back = Infinity;
  for (const point of surface) {
    if (Math.abs(point[1] - y) < 1.4 && point[0] < back) back = point[0];
  }
  return back;
}

/** The velum: a blend of its raised and lowered shapes.
 *
 *  Lowered, it comes to rest ON the back of the tongue — which for /ŋ/ is
 *  already up at the soft palate — so every point of the flap is lifted clear
 *  of whatever the tongue is doing underneath it. */
/** The two sides of the velum for this pose, before the tongue has a say:
 *  oral side and nasal side, each from the root to the uvula, paired point by
 *  point along their length. */
function velumSides(pose: Pose): { oral: Vec2[]; nasal: Vec2[] } {
  const open = clamp01(pose.velum);
  const blended = VELUM_RAISED.map((point, index) => lerpPoint(point, VELUM_LOWERED[index], open));
  const half = VELUM_POINTS / 2;
  return {
    oral: resample(spline(blended.slice(0, VELUM_UVULA + 1), 5), half),
    nasal: resample(spline(blended.slice(VELUM_UVULA + 1).reverse(), 5), half),
  };
}

/** Where `TRACT_ROOF` stops being the back wall of the pharynx and becomes
 *  the underside of the raised velum, and where the hard palate takes over
 *  (the spline puts landmark i at index 4·i). */
const ROOF_SEAL = 4 * 4;
const ROOF_PALATE = 6 * 4;

/**
 * The roof the tongue is stopped by, for this pose. Along the velum it is the
 * velum's own oral side, wherever the velum is: lowered for /ŋ/, the back of
 * the tongue meets it low down and the port to the nose stays open behind it,
 * instead of the tongue climbing to where a raised velum would be.
 */
export function roofFor(pose: Pose): readonly Vec2[] {
  if (clamp01(pose.velum) < 0.02) return TRACT_ROOF;
  const { oral } = velumSides(pose);
  const uvula = oral[oral.length - 1];
  // The back wall, up to the height of the uvula; across the port to the
  // uvula; up the oral side of the velum; then the hard palate onwards.
  const wall = TRACT_ROOF.slice(0, ROOF_SEAL + 1).filter(([, y]) => y < uvula[1]);
  const last = wall[wall.length - 1];
  return [...wall, [last[0], uvula[1]], ...oral.slice().reverse(), ...TRACT_ROOF.slice(ROOF_PALATE + 1)];
}

/** The velum as drawn: its oral side rests on the tongue where the tongue
 *  is under it, and the nasal side gives way just enough to keep the flap
 *  from folding — it drapes over the tongue instead of passing through it. */
function buildVelum(pose: Pose, surface: readonly Vec2[]): Vec2[] {
  const { oral, nasal } = velumSides(pose);
  const clearance = VELUM_CLEARANCE * clamp01(pose.velum);
  const lifted = oral.map(([x, y]): Vec2 => [x, Math.max(y, tongueTopAt(surface, x) + clearance)]);
  const nasalSide = nasal.map(([x, y], i): Vec2 => {
    const thickness = Math.hypot(x - oral[i][0], y - oral[i][1]);
    return [x, Math.max(y, lifted[i][1] + Math.min(0.6 * thickness, 1.8))];
  });
  return [...lifted, ...nasalSide.reverse()];
}

/** The epiglottis, pushed back by the root of the tongue: it is folded over
 *  the larynx by exactly that when the root retracts for /ɑ/. */
function buildEpiglottis(surface: readonly Vec2[]): Vec2[] {
  const base = EPIGLOTTIS[0][1];
  const tip = EPIGLOTTIS[EPIGLOTTIS_LINGUAL][1];
  let push = 0;
  for (let i = 1; i <= EPIGLOTTIS_LINGUAL; i += 1) {
    const [x, y] = EPIGLOTTIS[i];
    const room = tongueBackAt(surface, y) - 0.7 - x;
    if (room < 0) push = Math.max(push, -room / Math.max((y - base) / (tip - base), 0.25));
  }
  // It can only fold so far before it meets the back wall of the pharynx.
  push = Math.min(push, 2.6);
  const bent = EPIGLOTTIS.map(([x, y]): Vec2 => {
    const weight = clamp01((y - base) / (tip - base));
    return [x - push * weight * weight, y - push * 0.25 * weight];
  });
  return resample(spline(bent, 5, true), EPIGLOTTIS_POINTS, true);
}

/** The stretch of roof around the point a ray from the tongue centre meets. */
export function placeOnRoof(
  point: Vec2,
  center: Vec2 = TONGUE_CENTER,
  roof: readonly Vec2[] = TRACT_ROOF,
  reach = 2.6,
): Vec2[] {
  const angle = Math.atan2(point[1] - center[1], point[0] - center[0]);
  const wall = rayHit(center, angle, roof);
  const hit: Vec2 =
    wall === null ? point : [center[0] + Math.cos(angle) * wall, center[1] + Math.sin(angle) * wall];
  // Nearest roof sample, then walk the curve both ways by arc length.
  let nearest = 0;
  let best = Infinity;
  roof.forEach(([x, y], index) => {
    const distance = (x - hit[0]) ** 2 + (y - hit[1]) ** 2;
    if (distance < best) {
      best = distance;
      nearest = index;
    }
  });
  const walk = (step: number): number => {
    let index = nearest;
    let travelled = 0;
    while (index + step >= 0 && index + step < roof.length && travelled < reach) {
      const [ax, ay] = roof[index];
      const [bx, by] = roof[index + step];
      travelled += Math.hypot(bx - ax, by - ay);
      index += step;
    }
    return index;
  };
  const stretch = roof.slice(walk(-1), walk(1) + 1);
  return stretch.length >= 2 ? resample(stretch, PLACE_POINTS) : Array(PLACE_POINTS).fill(hit);
}

/** The airway: lips → behind the palate → nostril → roof of the nose →
 *  back of the pharynx → larynx → floor of the mouth → lips. Anything drawn
 *  over it is tissue; what is left uncovered is air. */
function buildAirway(jawTissue: readonly Vec2[], upperEdge: Vec2, lowerEdge: Vec2): Vec2[] {
  const front = 0.7;
  // The floor is drawn as a smooth curve through these landmarks; tucking the
  // airway a little under it keeps the curve from uncovering a sliver of
  // background between landmarks.
  const floor = jawTissue.slice(1, 15).reverse();
  const tucked = floor.map((point, i): Vec2 => {
    const a = floor[Math.max(i - 1, 0)];
    const b = floor[Math.min(i + 1, floor.length - 1)];
    const length = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1;
    // Travelling up the larynx and forwards along the floor, the tissue is on
    // the right-hand side.
    return [point[0] + ((b[1] - a[1]) / length) * 0.8, point[1] - ((b[0] - a[0]) / length) * 0.8];
  });
  return [
    [upperEdge[0] - front, upperEdge[1] + 0.3],
    [84.0, 44.6],
    [86.4, 49.6],
    [91.8, 49.7],    // nostril
    [94.0, 50.6],
    [92.0, 52.8],
    [87.0, 57.8],
    [81.0, 62.4],
    [72.0, 65.4],
    [60.0, 64.6],
    [54.0, 60.0],
    [49.6, 53.6],
    [46.2, 48.0],
    [45.2, 44.0],
    [44.8, 30.0],
    [45.8, 14.0],
    [48.0, 0],
    // larynx and floor of the mouth, from the bottom of the panel forwards
    ...tucked,
    [lowerEdge[0] - front, lowerEdge[1] - 0.3],
  ];
}

/**
 * Everything the renderer needs for one instant of speech.
 */
export function buildTract(pose: Pose): TractShapes {
  const jaw = jawAngle(pose);
  // The tongue is slung from the mandible, so it follows the jaw — but only
  // most of the way: it can stay high over an open jaw, and that difference is
  // exactly what separates /æ/ from /ɛ/.
  const center = rotateAround(TONGUE_CENTER, JAW_PIVOT, jaw * jawFollow(TONGUE_CENTER[0]));
  const floor = applyJawGraded(TONGUE_FLOOR, jaw);
  const roof = roofFor(pose);
  const surface = tongueSurface(pose, center, floor, jaw, roof);
  const lowerTeeth = applyJaw(LOWER_TEETH, jaw);
  const underside = tongueUnderside(pose, surface[surface.length - 1], jaw, lowerTeeth);

  const lips = buildLips(pose, jaw);
  const jawTissue = applyJawNeck(LOWER_FACE, jaw);

  // The narrowest point of the whole tract: the highlight follows it, so it
  // lands on the ridge for /t/, on the velum for /k/ and on the lips for /p/
  // without any of those cases being special-cased.
  let constriction: Constriction = { point: [0, 0], gap: Infinity, where: "tongue" };
  for (const point of surface) {
    const gap = gapToWall(point, center, roof);
    if (gap < constriction.gap) constriction = { point, gap, where: "tongue" };
  }
  const tongueConstriction = constriction;
  if (pose.lipTuck > 0.35) {
    const gap = (1 - pose.lipTuck) * 6;
    if (gap < constriction.gap) {
      constriction = { point: [80.9, 39.6], gap, where: "teeth" };
    }
  }
  if (lips.gap < constriction.gap) {
    // Between the two free edges, wherever rounding has pushed them.
    const point = lerpPoint(lips.upperEdge, lips.lowerEdge, 0.5);
    constriction = { point: [point[0] - 0.6, point[1]], gap: lips.gap, where: "lips" };
  }

  return {
    tongue: [...surface, ...underside.slice(1)],
    tongueSurface: surface,
    tongueUnderside: underside,
    upperLip: lips.upper,
    lowerLip: lips.lower,
    upperVermilion: lips.upperVermilion,
    lowerVermilion: lips.lowerVermilion,
    lowerTeeth,
    jawTissue,
    mandible: applyJaw(MANDIBLE, jaw),
    velum: buildVelum(pose, surface),
    epiglottis: buildEpiglottis(surface),
    hyoid: applyJawNeck(HYOID, jaw),
    thyroid: applyJawNeck(THYROID, jaw),
    muscleOrigin: rotateAround(GENIOGLOSSUS_ORIGIN, JAW_PIVOT, jaw),
    airway: buildAirway(jawTissue, lips.upperEdge, lips.lowerEdge),
    lipGap: lips.gap,
    constriction,
    place: placeOnRoof(tongueConstriction.point, center, roof),
  };
}
