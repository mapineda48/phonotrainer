/** The report's advice next to a phenomenon or a reduced form: something a
 *  learner can say too, or something to learn to recognize and leave alone.
 *  Encoded by text and border style, never by color alone. */

import { PRACTICE_LABEL } from "../reference";
import type { Practice } from "../types";

export function PracticeBadge({ practice }: { practice: Practice | null }) {
  if (!practice) return null;
  return (
    <span
      className={`practice practice--${practice.practice}`}
      title={practice.why}
      data-testid="practice-badge"
    >
      {PRACTICE_LABEL[practice.practice]}
      <span className="practice__register"> · {practice.register}</span>
    </span>
  );
}
