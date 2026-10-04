import { describe, expect, it } from "vitest";

import { TRACT_WALL, type Vec2 } from "./anatomy";
import { PHONE_TABLE, lookupPhone } from "./phones";
import { REST_POSE, makePose, type Pose } from "./pose";
import {
  LOWER_LIP_POINTS,
  PLACE_POINTS,
  TONGUE_SURFACE_POINTS,
  TONGUE_UNDERSIDE_POINTS,
  UPPER_LIP_POINTS,
  VELUM_POINTS,
  VERMILION_EXPOSED_POINTS,
  VERMILION_POINTS,
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
    // exactly where the velum is coming down. The two may touch; neither may
    // pass into the other.
    for (const symbol of ["ŋ", "ŋ̍", "n", "m", "ɾ̃"]) {
      const shapes = buildTract(poseOf(symbol));
      const deep = (point: Vec2, polygon: readonly Vec2[]) =>
        inside(point, polygon) &&
        Math.min(...polygon.map(([x, y]) => Math.hypot(x - point[0], y - point[1]))) > 0.35;
      expect(shapes.velum.filter((point) => deep(point, shapes.tongue)), symbol).toEqual([]);
      expect(shapes.tongueSurface.filter((point) => deep(point, shapes.velum)), symbol).toEqual([]);
    }
  });

  it("is met low down by the tongue for /ŋ/, leaving the port to the nose open", () => {
    const ng = buildTract(poseOf("ŋ"));
    const k = buildTract(poseOf("k"));
    expect(ng.constriction.gap).toBeLessThan(0.5);
    expect(ng.constriction.point[1]).toBeLessThan(k.constriction.point[1]);
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

const inside = ([x, y]: Vec2, polygon: readonly Vec2[]): boolean => {
  let hit = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i, i += 1) {
    const [xi, yi] = polygon[i];
    const [xj, yj] = polygon[j];
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) hit = !hit;
  }
  return hit;
};

const crosses = (a: Vec2, b: Vec2, c: Vec2, d: Vec2): boolean => {
  const side = (p: Vec2, q: Vec2, r: Vec2) => (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0]);
  return side(a, b, c) * side(a, b, d) < 0 && side(c, d, a) * side(c, d, b) < 0;
};

const selfCrossings = (polygon: readonly Vec2[]): number => {
  let count = 0;
  const n = polygon.length;
  for (let i = 0; i < n; i += 1) {
    for (let j = i + 2; j < n; j += 1) {
      if (i === 0 && j === n - 1) continue;
      if (crosses(polygon[i], polygon[(i + 1) % n], polygon[j], polygon[(j + 1) % n])) count += 1;
    }
  }
  return count;
};

describe("the place of articulation", () => {
  const middle = (points: readonly Vec2[]) => points[Math.floor(points.length / 2)];

  it("is the alveolar ridge for /t/ and the soft palate for /k/", () => {
    expect(buildTract(poseOf("t")).place).toHaveLength(PLACE_POINTS);
    const ridge = middle(buildTract(poseOf("t")).place);
    expect(ridge[0]).toBeGreaterThan(69);
    expect(ridge[0]).toBeLessThan(78);
    expect(middle(buildTract(poseOf("k")).place)[0]).toBeLessThan(58);
  });

  it("lies on the roof the tongue is clipped against", () => {
    for (const [x, y] of buildTract(poseOf("t")).place) {
      const roof = roofY(x);
      if (roof !== null) expect(Math.abs(y - roof)).toBeLessThan(0.5);
    }
  });
});

describe("drawable outlines", () => {
  it("never folds the airway, the tongue or the lips over themselves", () => {
    // A self-crossing outline cannot be triangulated: it would draw holes.
    const offenders: string[] = [];
    for (const phone of PHONE_TABLE) {
      for (const { pose } of phone.gestures) {
        const shapes = buildTract(pose);
        for (const name of ["airway", "tongue", "upperLip", "lowerLip", "velum"] as const) {
          if (selfCrossings(shapes[name]) > 0) offenders.push(`${phone.symbol}: ${name}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it("keeps the tongue out of the lower incisor, even between the teeth", () => {
    for (const symbol of ["θ", "ð", "t", "i", "ɑ"]) {
      const shapes = buildTract(poseOf(symbol));
      const crown = shapes.lowerTeeth;
      const sunk = shapes.tongueUnderside.slice(1).filter((point) => inside(point, crown));
      expect(sunk, symbol).toEqual([]);
    }
  });

  it("puts the vermilion on the lip's own edge", () => {
    const shapes = buildTract(poseOf("ə"));
    for (const [lip, red] of [
      [shapes.upperLip, shapes.upperVermilion],
      [shapes.lowerLip, shapes.lowerVermilion],
    ] as const) {
      expect(red).toHaveLength(VERMILION_POINTS);
      for (const point of red.slice(0, VERMILION_EXPOSED_POINTS)) {
        const nearest = Math.min(...lip.map(([x, y]) => Math.hypot(x - point[0], y - point[1])));
        expect(nearest).toBeLessThan(0.6);
      }
    }
  });
});

describe("the soft palate, as a shape", () => {
  it("seals against the back wall when raised and opens the port when lowered", () => {
    const back = (symbol: string) => Math.min(...buildTract(poseOf(symbol)).velum.map((p) => p[0]));
    expect(back("d")).toBeLessThan(46.5);
    expect(back("n")).toBeGreaterThan(back("d") + 2);
  });
});
