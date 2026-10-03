import { describe, expect, it } from "vitest";

import { metrics } from "../test/fixtures";
import { metricRows, standing } from "./metrics";

const range = { low: 20, high: 25, display: "20–25 %", cite: "c", source: "s", note: "n" };
const point = { low: 25, high: 25, display: "≈ 25 %", cite: "c", source: "s", note: "n" };
const floor = { low: 60, high: null, display: "> 60 %", cite: "c", source: "s", note: "n" };

describe("metrics", () => {
  it("places a value against a range, a point (±2) and a floor", () => {
    expect(standing(19, range)).toBe("below");
    expect(standing(23.5, range)).toBe("within");
    expect(standing(26, range)).toBe("above");
    expect(standing(26.9, point)).toBe("within");
    expect(standing(27.1, point)).toBe("above");
    expect(standing(72, floor)).toBe("within");
    expect(standing(42, floor)).toBe("below");
    expect(standing(null, range)).toBeNull();
    expect(standing(10, undefined)).toBeNull();
  });

  it("lists the headline measures first and the form scoring only when present", () => {
    const keys = metricRows(metrics).map((row) => row.key);
    expect(keys.slice(0, 3)).toEqual(["deviate", "segment_loss", "syllable_loss"]);
    expect(keys).not.toContain("weak_forms_variant");

    const scored = {
      ...metrics,
      weak_forms: {
        ...metrics.weak_forms,
        variant: { weak: 3, strong: 7, uncertain: 2, count: 3, of: 12, pct: 25 },
      },
    };
    expect(metricRows(scored).map((row) => row.key)).toContain("weak_forms_variant");
  });
});
