/** The focus ring is part of the accessibility contract (WCAG 2.4.7, 2.4.11): every
 *  control shows the same 3 px ring on keyboard focus. jsdom does not apply cascade
 *  layers, so this guards the stylesheet itself: the ring must be !important, or the
 *  `outline-none` utility that most controls carry (utilities beat the base layer)
 *  silently removes it, as it did before. */

import { describe, expect, it } from "vitest";

import css from "./index.css?raw";

describe("the focus ring", () => {
  it("is declared once for :focus-visible and [data-focus-visible], 3 px, and !important", () => {
    const rule = css.match(/:focus-visible,\s*\[data-focus-visible\]\s*\{([^}]*)\}/);
    expect(rule, "the focus-visible rule is missing from index.css").not.toBeNull();
    expect(rule![1]).toMatch(/outline:\s*3px solid var\(--focus\)\s*!important/);
  });

  it("is only removed for mouse focus, never for keyboard focus", () => {
    expect(css).toMatch(/:focus:not\(:focus-visible\):not\(\[data-focus-visible\]\)\s*\{\s*outline:\s*none;/);
  });
});
