/** Accessibility smoke test with axe-core (dev-only, MPL-2.0, never shipped).
 *
 *    await expectNoAxeViolations(container);
 *
 *  Runs every axe rule that makes sense under jsdom and fails on serious or critical
 *  violations, listing them. Color contrast is skipped here — jsdom has no layout or
 *  computed colors — because src/theme/palette.test.ts checks every token pair instead. */

import axe from "axe-core";
import { expect } from "vitest";

export interface AxeOptions {
  /** Extra rule ids to disable for this check (document why at the call site). */
  disable?: string[];
  /** Also fail on moderate/minor issues. */
  strict?: boolean;
}

export async function axeViolations(root: Element, options: AxeOptions = {}) {
  const rules: Record<string, { enabled: boolean }> = {
    "color-contrast": { enabled: false },
    // a component rendered alone is not a whole page
    region: { enabled: false },
    "landmark-one-main": { enabled: false },
    "page-has-heading-one": { enabled: false },
  };
  for (const id of options.disable ?? []) rules[id] = { enabled: false };
  const results = await axe.run(root, { rules, resultTypes: ["violations"] });
  return results.violations.filter(
    (v) => options.strict || v.impact === "serious" || v.impact === "critical",
  );
}

export async function expectNoAxeViolations(root: Element, options: AxeOptions = {}) {
  const violations = await axeViolations(root, options);
  const report = violations.map(
    (v) => `${v.impact}: ${v.id} — ${v.help}\n  ${v.nodes.map((n) => n.target.join(" ")).join("\n  ")}`,
  );
  expect(report, report.join("\n")).toEqual([]);
}
