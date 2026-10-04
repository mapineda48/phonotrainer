import { describe, expect, it } from "vitest";

import { fmtBytes, fmtDuration, fmtTime, plural } from "./format";

describe("plural", () => {
  it("only adds the s when the count is not one", () => {
    expect(plural(1, "occurrence")).toBe("1 occurrence");
    expect(plural(0, "occurrence")).toBe("0 occurrences");
    expect(plural(23, "occurrence")).toBe("23 occurrences");
  });
});

describe("fmtTime", () => {
  it("uses minutes and tenths", () => {
    expect(fmtTime(0)).toBe("0:00.0");
    expect(fmtTime(9.26)).toBe("0:09.3");
    expect(fmtTime(75.5)).toBe("1:15.5");
  });

  it("does not break on invalid values", () => {
    expect(fmtTime(Number.NaN)).toBe("0:00.0");
    expect(fmtTime(-4)).toBe("0:00.0");
  });

  it("rolls over to the next minute instead of writing 0:60.0", () => {
    expect(fmtTime(59.96)).toBe("1:00.0");
    expect(fmtTime(119.97)).toBe("2:00.0");
    expect(fmtTime(3599.98)).toBe("60:00.0");
  });
});

describe("fmtDuration", () => {
  it("switches from seconds to minutes", () => {
    expect(fmtDuration(42.678)).toBe("42.7 s");
    expect(fmtDuration(128)).toBe("2 min 8 s");
    expect(fmtDuration(null)).toBe("—");
  });
});

describe("fmtBytes", () => {
  it("picks the unit", () => {
    expect(fmtBytes(900)).toBe("900 B");
    expect(fmtBytes(65590549)).toBe("63 MB"); // ≥10 in its unit: no decimals
    expect(fmtBytes(5 * 1024)).toBe("5.0 kB");
    expect(fmtBytes(undefined)).toBe("");
  });
});
