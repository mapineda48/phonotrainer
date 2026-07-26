import { describe, expect, it } from "vitest";

import { fmtBytes, fmtDuration, fmtTime } from "./format";

describe("fmtTime", () => {
  it("usa minutos y décimas", () => {
    expect(fmtTime(0)).toBe("0:00.0");
    expect(fmtTime(9.26)).toBe("0:09.3");
    expect(fmtTime(75.5)).toBe("1:15.5");
  });

  it("no se rompe con valores inválidos", () => {
    expect(fmtTime(Number.NaN)).toBe("0:00.0");
    expect(fmtTime(-4)).toBe("0:00.0");
  });
});

describe("fmtDuration", () => {
  it("cambia de segundos a minutos", () => {
    expect(fmtDuration(42.678)).toBe("42.7 s");
    expect(fmtDuration(128)).toBe("2 min 8 s");
    expect(fmtDuration(null)).toBe("—");
  });
});

describe("fmtBytes", () => {
  it("elige la unidad", () => {
    expect(fmtBytes(900)).toBe("900 B");
    expect(fmtBytes(65590549)).toBe("63 MB"); // ≥10 en su unidad: sin decimales
    expect(fmtBytes(5 * 1024)).toBe("5.0 kB");
    expect(fmtBytes(undefined)).toBe("");
  });
});
