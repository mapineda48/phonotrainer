/** What the articulators are doing at one instant, as eleven numbers.
 *
 *  Everything the engine draws comes out of this vector, and everything a
 *  phone declares goes into it. Keeping it numeric (no "place" or "manner"
 *  here) is what makes movement possible: two poses can be blended, and that
 *  blend IS coarticulation — the tongue on its way from /k/ to /i/ passes
 *  through the intermediate shapes instead of teleporting.
 *
 *  Ranges are all 0..1 and every one of them is anchored to the anatomy:
 *  `height` = 1 is not "high", it is *touching the roof along that direction*.
 */

export interface Pose {
  /** Jaw rotation: 0 = teeth together, 1 = widest opening used in speech. */
  jaw: number;
  /** Lip aperture: 0 = lips closed (/p/), 1 = wide open (/ɑ/). */
  lipOpen: number;
  /** Rounding, which in a profile view is protrusion: 0 = spread, 1 = /u/. */
  lipRound: number;
  /** Lower lip tucked against the upper incisors: this alone is /f/ and /v/. */
  lipTuck: number;
  /** Tongue body front↔back: 0 = back (velar/pharyngeal), 1 = front (palatal). */
  body: number;
  /** Tongue body raising: 0 = flat on the floor, 1 = contact with the roof. */
  height: number;
  /** Tip raising: 0 = resting behind the lower teeth, 1 = contact. */
  tip: number;
  /** Where the tip aims: 0 = postalveolar, 0.5 = alveolar, 1 = between the teeth. */
  tipFront: number;
  /** Tongue root: 0 = advanced (wide pharynx), 1 = retracted (narrow). */
  root: number;
  /** Velum: 0 = raised, air only through the mouth; 1 = lowered, nasal. */
  velum: number;
  /** Vocal folds: 0 = voiceless, 1 = voiced. */
  voice: number;
}

/** Silence: the tract at rest, which is where the tongue returns between
 *  phrases. Not a neutral zero — a mouth at rest is a real configuration
 *  (lips gently together, tongue mid, velum raised, no voice). */
export const REST_POSE: Pose = {
  jaw: 0.12,
  lipOpen: 0.06,
  lipRound: 0.05,
  lipTuck: 0,
  body: 0.5,
  height: 0.42,
  tip: 0.1,
  tipFront: 0.5,
  root: 0.3,
  velum: 0.25,
  voice: 0,
};

export const POSE_KEYS = Object.keys(REST_POSE) as (keyof Pose)[];

/** A pose with only some articulators stated; the rest come from `REST_POSE`. */
export type PartialPose = Partial<Pose>;

export function makePose(partial: PartialPose): Pose {
  return { ...REST_POSE, ...partial };
}

/** Blend two poses. `t` is already eased by the caller when it needs to be. */
export function blendPose(from: Pose, to: Pose, t: number): Pose {
  if (t <= 0) return from;
  if (t >= 1) return to;
  const out = {} as Pose;
  for (const key of POSE_KEYS) out[key] = from[key] + (to[key] - from[key]) * t;
  return out;
}

/** Smoothstep. Articulators accelerate and decelerate; a linear ramp between
 *  two targets reads as a machine, not as a mouth. */
export const ease = (t: number): number => {
  const clamped = t <= 0 ? 0 : t >= 1 ? 1 : t;
  return clamped * clamped * (3 - 2 * clamped);
};

/**
 * Jaw and lip aperture that go with a vowel of this height.
 *
 * They are not free parameters: an open vowel is open *because* the jaw drops.
 * Deriving them keeps the table honest (no /i/ with a dropped jaw by a typo)
 * and any vowel that needs to depart from it still can, by stating its own.
 */
export function vowelJaw(height: number): number {
  return 0.9 - 0.78 * height;
}

export function vowelLipOpen(jaw: number, round: number): number {
  return (0.14 + 0.78 * jaw) * (1 - 0.5 * round);
}
