import { describe, expect, it } from "vitest";

import { TRACT_WALL, type Vec2 } from "./anatomy";
import { PHONE_TABLE, lookupPhone } from "./phones";
import { REST_POSE, makePose, type Pose } from "./pose";
import {
  LOWER_LIP_POINTS,
  TONGUE_SURFACE_POINTS,
  TONGUE_UNDERSIDE_POINTS,
  UPPER_LIP_POINTS,
  VELUM_POINTS,
  buildTract,
} from "./tract";

/** Poses a phone actually takes (every one of its targets). */
const posesOf = (symbol: string): Pose[] =>
  (lookupPhone(symbol)?.gestures ?? []).map((gesture) => gesture.pose);

const poseOf = (symbol: string): Pose => {
  const poses = posesOf(symbol);
  if (poses.length === 0) throw new Error(`no articulation for ${symbol}`);
  return poses[poses.length - 1];
};

/** Height of the roof over a point, interpolated along the wall. Only valid
 *  where the wall advances in x, which is from the velar seal forwards. */
function roofY(x: number): number | null {
  const front = TRACT_WALL.slice(4); // from the velar seal to the incisors
  if (x < front[0][0] || x > front[front.length - 1][0]) return null;
  for (let i = 0; i < front.length - 1; i += 1) {
    const [ax, ay] = front[i];
    const [bx, by] = front[i + 1];
    if (x >= ax && x <= bx) return ay + ((by - ay) * (x - ax)) / (bx - ax);
  }
  return null;
}

const highest = (points: readonly Vec2[]): Vec2 =>
  points.reduce((best, point) => (point[1] > best[1] ? point : best));

describe("tongue against the anatomy", () => {
  it("never crosses the palate, for any phone and any of its targets", () => {
    // This is the invariant the whole model is built on: the surface is cast
    // as rays that stop at the roof, so a phone that pushes harder makes
    // contact instead of going through the head.
    const offenders: string[] = [];
    for (const phone of PHONE_TABLE) {
      for (const { pose } of phone.gestures) {
        for (const [x, y] of buildTract(pose).tongueSurface) {
          // Past the upper incisors there is no roof — that gap is precisely
          // where the tip comes out for /θ/ and /ð/.
          if (x > 79) continue;
          const roof = roofY(x);
          if (roof !== null && y > roof + 0.3) offenders.push(`${phone.symbol} (${x.toFixed(1)}, ${y.toFixed(1)} > ${roof.toFixed(1)})`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it("never pushes the root through the back of the pharynx", () => {
    for (const phone of PHONE_TABLE) {
      for (const { pose } of phone.gestures) {
        const backmost = Math.min(...buildTract(pose).tongueSurface.map((point) => point[0]));
        expect(backmost, phone.symbol).toBeGreaterThan(45);
      }
    }
  });

  it("returns outlines of a fixed size, so the renderer can allocate once", () => {
    const shapes = buildTract(REST_POSE);
    expect(shapes.tongueSurface).toHaveLength(TONGUE_SURFACE_POINTS);
    expect(shapes.tongue).toHaveLength(TONGUE_SURFACE_POINTS + TONGUE_UNDERSIDE_POINTS - 1);
    expect(shapes.upperLip).toHaveLength(UPPER_LIP_POINTS);
    expect(shapes.lowerLip).toHaveLength(LOWER_LIP_POINTS);
    expect(shapes.velum).toHaveLength(VELUM_POINTS);
    for (const phone of PHONE_TABLE) {
      for (const { pose } of phone.gestures) {
        expect(buildTract(pose).tongueSurface, phone.symbol).toHaveLength(TONGUE_SURFACE_POINTS);
      }
    }
  });
});

describe("where each phone closes the tract", () => {
  it("puts /t/ on the alveolar ridge", () => {
    const { constriction } = buildTract(poseOf("t"));
    expect(constriction.where).toBe("tongue");
    expect(constriction.gap).toBeLessThan(0.5);
    expect(constriction.point[0]).toBeGreaterThan(69);
    expect(constriction.point[0]).toBeLessThan(78);
    expect(constriction.point[1]).toBeGreaterThan(42);
  });

  it("puts /k/ at the back, against the soft palate", () => {
    const { constriction } = buildTract(poseOf("k"));
    expect(constriction.where).toBe("tongue");
    expect(constriction.gap).toBeLessThan(0.5);
    expect(constriction.point[0]).toBeLessThan(58);
  });

  it("puts /p/ at the lips and /f/ at the teeth", () => {
    expect(buildTract(poseOf("p")).constriction.where).toBe("lips");
    expect(buildTract(poseOf("p")).constriction.gap).toBe(0);
    expect(buildTract(poseOf("f")).constriction.where).toBe("teeth");
  });

  it("leaves a channel for the fricatives instead of closing", () => {
    const groove = buildTract(poseOf("s")).constriction;
    expect(groove.gap).toBeGreaterThan(0);
    expect(groove.gap).toBeLessThan(3);
    // and a vowel is wide open by comparison
    expect(buildTract(poseOf("ɛ")).constriction.gap).toBeGreaterThan(groove.gap * 2);
  });

  it("sends the tip of /θ/ out between the teeth", () => {
    const tip = buildTract(poseOf("θ")).tongueSurface.at(-1)!;
    expect(tip[0]).toBeGreaterThan(80.6); // past the upper incisors
    expect(buildTract(poseOf("t")).tongueSurface.at(-1)![0]).toBeLessThan(80);
  });
});

describe("the vowel space, drawn", () => {
  it("puts /i/ high and forward and /ɑ/ low and back", () => {
    const front = highest(buildTract(poseOf("i")).tongueSurface);
    const back = highest(buildTract(poseOf("ɑ")).tongueSurface);
    expect(front[1]).toBeGreaterThan(back[1] + 5);
    // Where the tract narrows is what tells the two apart, not where the
    // surface happens to peak: a tongue lying flat peaks wherever the floor
    // rises, which is at the front and means nothing.
    expect(buildTract(poseOf("i")).constriction.point[0]).toBeGreaterThan(
      buildTract(poseOf("ɑ")).constriction.point[0] + 5,
    );
  });

  it("drops the jaw for the open vowels", () => {
    const open = buildTract(poseOf("æ")).lowerTeeth[2][1];
    const closed = buildTract(poseOf("i")).lowerTeeth[2][1];
    expect(open).toBeLessThan(closed - 2);
  });

  it("pushes the lips forward when they round", () => {
    const rounded = buildTract(poseOf("u")).upperLip;
    const spread = buildTract(poseOf("i")).upperLip;
    expect(Math.max(...rounded.map((p) => p[0]))).toBeGreaterThan(
      Math.max(...spread.map((p) => p[0])) + 1.5,
    );
  });

  it("narrows the pharynx for /ɑ/ and widens it for /i/", () => {
    const backOf = (symbol: string) =>
      Math.min(...buildTract(poseOf(symbol)).tongueSurface.map((point) => point[0]));
    expect(backOf("ɑ")).toBeLessThan(backOf("i") - 0.5);
  });
});

describe("the velum", () => {
  const lowest = (points: readonly Vec2[]) => Math.min(...points.map((point) => point[1]));

  it("hangs down for a nasal and seals for its oral twin", () => {
    expect(lowest(buildTract(poseOf("n")).velum)).toBeLessThan(
      lowest(buildTract(poseOf("d")).velum) - 4,
    );
  });

  it("never sinks into the tongue when it drops", () => {
    // /ŋ/ is the hard case: the back of the tongue is up at the soft palate
    // exactly where the velum is coming down.
    const shapes = buildTract(poseOf("ŋ"));
    const tip = shapes.velum.reduce((best, point) => (point[1] < best[1] ? point : best));
    const under = shapes.tongueSurface.filter((point) => Math.abs(point[0] - tip[0]) < 3);
    for (const point of under) expect(point[1]).toBeLessThan(tip[1] + 0.01);
  });
});

describe("the lips", () => {
  it("closes them completely for /p/ and not for /ə/", () => {
    expect(buildTract(poseOf("p")).lipGap).toBe(0);
    expect(buildTract(poseOf("ə")).lipGap).toBeGreaterThan(2);
  });

  it("keeps the lips shut over an open jaw", () => {
    // /m/ in "arm": the jaw is still low from the vowel and the lips close
    // anyway. If the lower lip only followed the jaw, this would gape.
    const shut = buildTract(makePose({ ...poseOf("m"), jaw: 0.8 }));
    expect(shut.lipGap).toBe(0);
  });
});
