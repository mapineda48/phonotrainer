import { describe, expect, it } from "vitest";

import { lookupPhone } from "./phones";
import { REST_POSE } from "./pose";
import { buildTrack, phoneAt, poseAt, type TimedPhone } from "./track";

/** Peaks the way the analysis delivers them: an instant per phone. */
const peaks = (symbols: string[], step = 0.08, from = 1): TimedPhone[] =>
  symbols.map((symbol, index) => {
    const start = Number((from + index * step).toFixed(3));
    return [symbol, start, Number((start + 0.02).toFixed(3))] as TimedPhone;
  });

describe("buildTrack", () => {
  it("stretches each peak up to the next one", () => {
    // A 20 ms peak cannot be drawn. What can is the stretch it owns.
    const track = buildTrack(peaks(["t", "u"]));
    expect(track.phones[0].start).toBeCloseTo(1, 3);
    expect(track.phones[0].end).toBeCloseTo(1.08, 3);
    expect(track.phones[1].start).toBeCloseTo(1.08, 3);
  });

  it("does not let one phone hold for an entire pause", () => {
    const track = buildTrack([
      ["s", 1, 1.02],
      ["s", 3, 3.02],
    ]);
    expect(track.phones[0].end).toBeLessThan(1.3);
  });

  it("comes back to rest inside a silence", () => {
    const track = buildTrack([
      ["t", 1, 1.02],
      ["t", 3, 3.02],
    ]);
    expect(poseAt(track, 2).tip).toBeCloseTo(REST_POSE.tip, 2);
    expect(poseAt(track, 2).voice).toBeCloseTo(REST_POSE.voice, 2);
  });

  it("starts and ends at rest", () => {
    const track = buildTrack(peaks(["m", "i"]));
    expect(poseAt(track, 0)).toEqual(REST_POSE);
    expect(poseAt(track, 99)).toEqual(REST_POSE);
  });

  it("survives an empty list", () => {
    expect(poseAt(buildTrack([]), 1)).toEqual(REST_POSE);
    expect(phoneAt(buildTrack([]), 1)).toBeNull();
  });

  it("holds the shape of a phone it cannot draw instead of inventing one", () => {
    const track = buildTrack([
      ["u", 1, 1.02],
      ["ʕ", 1.08, 1.1], // not in the English inventory
      ["u", 1.16, 1.18],
    ]);
    expect(track.phones[1].articulation).toBeNull();
    expect(poseAt(track, 1.09).lipRound).toBeGreaterThan(0.7);
  });
});

describe("poseAt", () => {
  it("reaches the target of the phone that is sounding", () => {
    const track = buildTrack(peaks(["ɑ", "t", "ɑ"]));
    const closure = track.phones[1];
    const pose = poseAt(track, (closure.start + closure.end) / 2);
    expect(pose.tip).toBeGreaterThan(0.95);
    expect(pose.voice).toBeLessThan(0.2);
  });

  it("passes through the shapes in between, which is what coarticulation is", () => {
    const track = buildTrack(peaks(["i", "u"], 0.16));
    const mid = poseAt(track, track.phones[1].start);
    const from = lookupPhone("i")!.gestures[0].pose;
    const to = lookupPhone("u")!.gestures[0].pose;
    expect(mid.lipRound).toBeGreaterThan(from.lipRound);
    expect(mid.lipRound).toBeLessThan(to.lipRound);
    expect(mid.body).toBeLessThan(from.body);
    expect(mid.body).toBeGreaterThan(to.body);
  });

  it("draws the diphthong as a movement and not as a position", () => {
    const track = buildTrack([["aɪ", 1, 1.02]]);
    const phone = track.phones[0];
    const early = poseAt(track, phone.start + (phone.end - phone.start) * 0.2);
    const late = poseAt(track, phone.start + (phone.end - phone.start) * 0.85);
    expect(late.height).toBeGreaterThan(early.height + 0.3);
  });

  it("gives /h/ the mouth of its neighbours and takes away the voice", () => {
    // The /h/ of "he" is already an [i]; the one of "who" is rounded. Stamping
    // a shape of its own on it would be inventing an articulation.
    const front = buildTrack(peaks(["h", "i"]));
    const back = buildTrack(peaks(["h", "u"]));
    const at = (track: ReturnType<typeof buildTrack>) =>
      poseAt(track, track.phones[0].start + 0.04);
    expect(at(front).body).toBeGreaterThan(at(back).body);
    expect(at(back).lipRound).toBeGreaterThan(at(front).lipRound);
    expect(at(front).voice).toBeLessThan(0.5);
  });

  it("keeps the glottal stop silent without moving the mouth", () => {
    const track = buildTrack(peaks(["æ", "ʔ", "æ"]));
    const stop = track.phones[1];
    const pose = poseAt(track, (stop.start + stop.end) / 2);
    expect(pose.voice).toBeLessThan(0.3);
    expect(pose.jaw).toBeGreaterThan(0.5); // still open, as in the vowel around it
  });
});

describe("phoneAt", () => {
  it("names the phone that owns the instant", () => {
    const track = buildTrack(peaks(["d", "ɪ", "d"]));
    expect(phoneAt(track, 1.01)?.symbol).toBe("d");
    expect(phoneAt(track, 1.1)?.symbol).toBe("ɪ");
    expect(phoneAt(track, 1.1)?.index).toBe(1);
  });

  it("says nothing when nothing is sounding", () => {
    const track = buildTrack(peaks(["d"]));
    expect(phoneAt(track, 0.5)).toBeNull();
    expect(phoneAt(track, 9)).toBeNull();
  });
});
