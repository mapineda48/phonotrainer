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
  JAW_MAX_ANGLE,
  JAW_PIVOT,
  LIP_LINE_Y,
  LIP_MAX_GAP,
  LIP_PROTRUSION,
  LOWER_FACE,
  MANDIBLE_SECTION,
  LOWER_TEETH,
  MOUTH_FLOOR,
  TONGUE_CENTER,
  TONGUE_FLOOR,
  TRACT_WALL,
  VELUM_CLOSED_TIP,
  VELUM_OPEN_TIP,
  VELUM_ROOT,
  lerp,
  lerpPoint,
  rayHit,
  rotateAround,
  smoothPath,
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
const VELUM_CLEARANCE = 1.4;
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
  upperLip: Vec2[];
  lowerLip: Vec2[];
  lowerTeeth: Vec2[];
  /** Chin, floor of the mouth and the skin under the jaw, already rotated. */
  jawTissue: Vec2[];
  /** The mandible in section, carried by the jaw like everything else on it. */
  mandible: Vec2[];
  velum: Vec2[];
  /** Vertical distance between the lips, in units. */
  lipGap: number;
  /** The tightest point of the tract: the highlight, and where noise is born. */
  constriction: Constriction;
}

/** Jaw rotation for a pose. Negative because opening swings the chin down. */
export const jawAngle = (pose: Pose): number => -pose.jaw * JAW_MAX_ANGLE;

const applyJaw = (points: readonly Vec2[], angle: number): Vec2[] =>
  points.map((point) => rotateAround(point, JAW_PIVOT, angle));

const jawFollow = (x: number): number =>
  clamp01((x - JAW_FOLLOW_BACK) / (JAW_FOLLOW_FRONT - JAW_FOLLOW_BACK));

const applyJawGraded = (points: readonly Vec2[], angle: number): Vec2[] =>
  points.map((point) => rotateAround(point, JAW_PIVOT, angle * jawFollow(point[0])));

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
  const blade = 0.5 * pose.tip * gaussian(angle, apexAngle + 13 * DEG, 15 * DEG);
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
function tongueSurface(pose: Pose, center: Vec2, floor: readonly Vec2[], jaw: number): Vec2[] {
  const apex = apexPoint(pose, center, jaw);
  const apexAngle = Math.atan2(apex[1] - center[1], apex[0] - center[0]);
  // The last body ray sits just behind the tip, so the surface keeps moving
  // forward and never doubles back on itself.
  const lastRay = Math.max(apexAngle + 9 * DEG, 45 * DEG);

  const controls: Vec2[] = [];
  for (let i = 0; i < BODY_RAYS; i += 1) {
    const angle = ROOT_ANGLE + ((lastRay - ROOT_ANGLE) * i) / (BODY_RAYS - 1);
    const low = rayHit(center, angle, floor);
    const wall = rayHit(center, angle, TRACT_WALL);
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
    const wall = rayHit(center, angle, TRACT_WALL);
    if (wall === null) return point;
    const radius = Math.hypot(point[0] - center[0], point[1] - center[1]);
    if (radius <= wall) return point;
    return [center[0] + Math.cos(angle) * wall, center[1] + Math.sin(angle) * wall];
  });
}

/** Distance from a point to the roof, measured along the ray that produced it. */
function gapToWall(point: Vec2, center: Vec2): number {
  const angle = Math.atan2(point[1] - center[1], point[0] - center[0]);
  const wall = rayHit(center, angle, TRACT_WALL);
  if (wall === null) return Infinity; // past the incisors there is no roof
  const radius = Math.hypot(point[0] - center[0], point[1] - center[1]);
  return wall - radius;
}

/** Lip outlines. Each point carries how much it follows the aperture and the
 *  protrusion, so the lips deform instead of sliding as a block. */
const UPPER_LIP: readonly { point: Vec2; open: number; round: number }[] = [
  { point: [83.4, 47.2], open: 0, round: 0.15 },   // tucked under the tissue
  { point: [88.2, 47.6], open: 0.12, round: 0.6 },
  { point: [90.2, 43.8], open: 0.45, round: 1 },   // outer vermilion
  { point: [88.6, 40.4], open: 1, round: 1 },      // free edge
  { point: [85.8, 41.8], open: 0.8, round: 0.45 },
  { point: [83.8, 43.8], open: 0.2, round: 0.1 },
];

const LOWER_LIP: readonly { point: Vec2; open: number; round: number }[] = [
  { point: [83.6, 35.0], open: 0.25, round: 0.1 },
  { point: [85.8, 37.4], open: 0.85, round: 0.5 },
  { point: [88.4, 38.0], open: 1, round: 1 },     // free edge
  { point: [90.0, 35.2], open: 0.5, round: 1 },   // outer vermilion
  { point: [88.4, 30.6], open: 0.15, round: 0.5 },
  { point: [85.2, 29.6], open: 0, round: 0.1 },   // tucked under the chin
];

/** Where the lower lip goes for /f/ and /v/: tucked under the upper incisors. */
const LIP_TUCK_TARGET: Vec2 = [81.6, 39.4];

export const UPPER_LIP_POINTS = 14;
export const LOWER_LIP_POINTS = 14;
export const VELUM_POINTS = 16;

function buildLips(pose: Pose, jaw: number): { upper: Vec2[]; lower: Vec2[]; gap: number } {
  const gap = clamp01(pose.lipOpen) * LIP_MAX_GAP;
  const protrusion = clamp01(pose.lipRound) * LIP_PROTRUSION;
  // The lip line itself drops a little with the jaw: the whole mouth moves.
  const line = LIP_LINE_Y - pose.jaw * 2.2;

  const upperEdge = line + gap / 2;
  const upperShift = upperEdge - UPPER_LIP[3].point[1];
  const upper = UPPER_LIP.map(({ point, open, round }): Vec2 => [
    point[0] + protrusion * round,
    point[1] + upperShift * open,
  ]);

  // The lower lip rides on the jaw, and then the lip muscles correct whatever
  // aperture the jaw left: that is how /p/ can close over an open jaw.
  const rotated = LOWER_LIP.map(({ point }) => rotateAround(point, JAW_PIVOT, jaw));
  const lowerEdge = line - gap / 2;
  const lowerShift = lowerEdge - rotated[2][1];
  const tuck = clamp01(pose.lipTuck);
  const lower = rotated.map((point, index): Vec2 => {
    const { open, round } = LOWER_LIP[index];
    const moved: Vec2 = [point[0] + protrusion * round, point[1] + lowerShift * open];
    if (tuck === 0) return moved;
    const target = lerpPoint(moved, LIP_TUCK_TARGET, tuck * open);
    return target;
  });

  return {
    upper: smoothPath([...upper, upper[0]], UPPER_LIP_POINTS),
    lower: smoothPath([...lower, lower[0]], LOWER_LIP_POINTS),
    gap: Math.max(0, upperEdge - lowerEdge),
  };
}

/** Top of the tongue directly under `x`, or -Infinity if it is not there. */
function tongueTopAt(surface: readonly Vec2[], x: number): number {
  let top = -Infinity;
  for (const point of surface) {
    if (Math.abs(point[0] - x) < 1.6 && point[1] > top) top = point[1];
  }
  return top;
}

/** The velum as a tapered flap hinged on the back edge of the hard palate.
 *
 *  Lowered, it comes to rest ON the back of the tongue — which for /ŋ/ is
 *  already up at the soft palate — so every point of the flap is lifted clear
 *  of whatever the tongue is doing underneath it. */
function buildVelum(pose: Pose, surface: readonly Vec2[]): Vec2[] {
  const tip = lerpPoint(VELUM_CLOSED_TIP, VELUM_OPEN_TIP, clamp01(pose.velum));
  const mid = lerpPoint(VELUM_ROOT, tip, 0.55);
  const top = smoothPath([VELUM_ROOT, [mid[0] + 1.4, mid[1] + 0.4], tip], VELUM_POINTS / 2);
  const bottom = smoothPath(
    [tip, [mid[0] - 1.6, mid[1] - 0.6], [VELUM_ROOT[0] - 1.6, VELUM_ROOT[1] - 1.4]],
    VELUM_POINTS / 2,
  );
  return [...top, ...bottom].map((point): Vec2 => {
    const floor = tongueTopAt(surface, point[0]) + VELUM_CLEARANCE;
    return point[1] < floor ? [point[0], floor] : point;
  });
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
  const surface = tongueSurface(pose, center, floor, jaw);

  const underside = smoothPath(
    [surface[surface.length - 1], ...applyJawGraded(MOUTH_FLOOR, jaw)],
    TONGUE_UNDERSIDE_POINTS,
  );

  const lips = buildLips(pose, jaw);

  // The narrowest point of the whole tract: the highlight follows it, so it
  // lands on the ridge for /t/, on the velum for /k/ and on the lips for /p/
  // without any of those cases being special-cased.
  let constriction: Constriction = { point: [0, 0], gap: Infinity, where: "tongue" };
  for (const point of surface) {
    const gap = gapToWall(point, center);
    if (gap < constriction.gap) constriction = { point, gap, where: "tongue" };
  }
  if (pose.lipTuck > 0.35) {
    const gap = (1 - pose.lipTuck) * 6;
    if (gap < constriction.gap) {
      constriction = { point: [80.8, 39.6], gap, where: "teeth" };
    }
  }
  if (lips.gap < constriction.gap) {
    constriction = { point: [88.4, LIP_LINE_Y - pose.jaw * 2.2], gap: lips.gap, where: "lips" };
  }

  return {
    tongue: [...surface, ...underside.slice(1)],
    tongueSurface: surface,
    tongueUnderside: underside,
    upperLip: lips.upper,
    lowerLip: lips.lower,
    lowerTeeth: applyJaw(LOWER_TEETH, jaw),
    jawTissue: applyJawGraded(LOWER_FACE, jaw),
    mandible: applyJaw(MANDIBLE_SECTION, jaw),
    velum: buildVelum(pose, surface),
    lipGap: lips.gap,
    constriction,
  };
}
