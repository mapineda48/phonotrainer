/** Turning a list of timed phones into a continuous mouth.
 *
 *  The analysis gives CTC peaks: an instant per phone, not a duration. A mouth
 *  cannot be drawn from instants, so each phone is given the stretch that runs
 *  up to the next one (capped, or speech would freeze across a pause), its
 *  targets are laid out inside that stretch, and everything in between is
 *  interpolated. That interpolation is not decoration: the shapes the tongue
 *  passes through on its way from one target to the next are coarticulation,
 *  which is the whole subject of this program.
 */

import { lookupPhone, type PhoneArticulation } from "./phones";
import { REST_POSE, blendPose, ease, makePose, type Pose } from "./pose";

/** [symbol, start, end] in absolute seconds — the analysis' own shape. */
export type TimedPhone = readonly [string, number, number];

/** Longest a single phone is allowed to hold its target. Beyond this the mouth
 *  relaxes: nobody keeps a /t/ closed for half a second. */
const MAX_PHONE = 0.24;
/** Shortest stretch a phone gets, so a peak at the very end still shows. */
const MIN_PHONE = 0.05;
/** A gap wider than this is a silence: the tract goes back to rest inside it. */
const SILENCE_GAP = 0.3;
/** Lead-in and tail: the mouth is already moving before the first phone sounds. */
const LEAD = 0.12;

export interface TrackPhone {
  symbol: string;
  articulation: PhoneArticulation | null;
  /** Stretch this phone owns, after the peaks have been turned into spans. */
  start: number;
  end: number;
  /** Position in the source list, so the caller can map back to its own data. */
  index: number;
}

export interface Track {
  phones: TrackPhone[];
  keys: { time: number; pose: Pose }[];
  start: number;
  end: number;
}

export const EMPTY_TRACK: Track = { phones: [], keys: [], start: 0, end: 0 };

/** Spans from peaks: each phone runs until the next one starts. */
function spans(phones: readonly TimedPhone[]): { start: number; end: number }[] {
  return phones.map(([, start, end], index) => {
    const next = phones[index + 1];
    const limit = next ? Math.min(next[1], start + MAX_PHONE) : Math.max(end, start) + MAX_PHONE;
    return { start, end: Math.max(limit, start + MIN_PHONE) };
  });
}

function keysFor(phone: TrackPhone): { time: number; pose: Pose }[] {
  const articulation = phone.articulation;
  if (!articulation || articulation.gestures.length === 0) return [];
  const width = phone.end - phone.start;
  return articulation.gestures.map((gesture) => ({
    time: phone.start + gesture.at * width,
    pose: gesture.pose,
  }));
}

/**
 * Build the track. Phones with no oral target of their own (/h/, /ʔ/) are laid
 * down in a second pass: they take the shape the track already has at that
 * instant and only override what they really control, which is why the /h/ of
 * "he" shows an [i] tongue and the one in "who" a rounded one.
 */
export function buildTrack(phones: readonly TimedPhone[]): Track {
  if (phones.length === 0) return EMPTY_TRACK;

  const bounds = spans(phones);
  const tracked: TrackPhone[] = phones.map(([symbol], index) => ({
    symbol,
    articulation: lookupPhone(symbol),
    start: bounds[index].start,
    end: bounds[index].end,
    index,
  }));

  const keys: { time: number; pose: Pose }[] = [];
  const start = tracked[0].start;
  const end = tracked[tracked.length - 1].end;
  keys.push({ time: start - LEAD, pose: REST_POSE });

  for (const phone of tracked) {
    if (phone.articulation?.carry) continue;
    keys.push(...keysFor(phone));
  }

  // Silences: let the mouth come back to rest instead of gliding for a second
  // and a half between two words.
  for (let i = 0; i < tracked.length - 1; i += 1) {
    const gap = tracked[i + 1].start - tracked[i].end;
    if (gap > SILENCE_GAP) {
      keys.push({ time: tracked[i].end + gap * 0.35, pose: REST_POSE });
      keys.push({ time: tracked[i + 1].start - gap * 0.35, pose: REST_POSE });
    }
  }

  keys.push({ time: end + LEAD, pose: REST_POSE });
  keys.sort((a, b) => a.time - b.time);

  const oral: Track = { phones: tracked, keys, start, end };
  const inherited: { time: number; pose: Pose }[] = [];
  for (const phone of tracked) {
    const carry = phone.articulation?.carry;
    if (!carry) continue;
    const width = phone.end - phone.start;
    for (const at of [0.2, 0.8]) {
      const time = phone.start + at * width;
      inherited.push({ time, pose: makePose({ ...poseAt(oral, time), ...carry }) });
    }
  }
  if (inherited.length > 0) {
    keys.push(...inherited);
    keys.sort((a, b) => a.time - b.time);
  }

  return { phones: tracked, keys, start, end };
}

/** The pose at an instant, eased between the two surrounding targets. */
export function poseAt(track: Track, time: number): Pose {
  const keys = track.keys;
  if (keys.length === 0) return REST_POSE;
  if (time <= keys[0].time) return keys[0].pose;
  const last = keys[keys.length - 1];
  if (time >= last.time) return last.pose;

  let low = 0;
  let high = keys.length - 1;
  while (high - low > 1) {
    const mid = (low + high) >> 1;
    if (keys[mid].time <= time) low = mid;
    else high = mid;
  }
  const from = keys[low];
  const to = keys[high];
  const width = to.time - from.time;
  if (width <= 0) return to.pose;
  return blendPose(from.pose, to.pose, ease((time - from.time) / width));
}

/** Which phone owns this instant, or null in a silence. */
export function phoneAt(track: Track, time: number): TrackPhone | null {
  const phones = track.phones;
  if (phones.length === 0) return null;
  let low = 0;
  let high = phones.length - 1;
  let found = -1;
  while (low <= high) {
    const mid = (low + high) >> 1;
    if (phones[mid].start <= time) {
      found = mid;
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }
  if (found === -1) return null;
  return time <= phones[found].end ? phones[found] : null;
}

/** A track that never moves: one pose, held. It is what the panel shows when
 *  the user picks a phone by hand instead of playing it, and what the lab page
 *  drives with its sliders. */
export const staticTrack = (pose: Pose): Track => ({
  phones: [],
  keys: [{ time: 0, pose }],
  start: 0,
  end: 0,
});

/** Centre of a phone's stretch: where its target is fully reached, and so the
 *  instant to show when the user picks it by hand instead of by playing. */
export const phoneCenter = (phone: TrackPhone): number => (phone.start + phone.end) / 2;
