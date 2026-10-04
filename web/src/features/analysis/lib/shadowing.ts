/** The shadowing loop of the lesson's Practice step, as a plain plan of phases.
 *
 *  Listen at a slower speed, then a silent gap of the same length for the learner to
 *  say it, three times; then once more at full speed. Keeping the plan pure keeps the
 *  timing testable without a real audio element. */

export interface ShadowPhase {
  kind: "listen" | "repeat";
  /** 1-based round; the last round is the full-speed one. */
  round: number;
  rounds: number;
  rate: number;
  /** How long the phase lasts, in ms. */
  ms: number;
}

/** Extra silence after the gap, so the learner is not cut off mid-word. */
export const REPEAT_MARGIN_MS = 400;

export function shadowingPlan(
  span: { start: number; end: number },
  slowRate: number,
  slowRounds = 3,
): ShadowPhase[] {
  const seconds = Math.max(0.2, span.end - span.start);
  const rounds = slowRounds + (slowRate === 1 ? 0 : 1);
  const phases: ShadowPhase[] = [];
  for (let round = 1; round <= rounds; round += 1) {
    const rate = round <= slowRounds ? slowRate : 1;
    const ms = Math.round((seconds / rate) * 1000);
    phases.push({ kind: "listen", round, rounds, rate, ms });
    phases.push({ kind: "repeat", round, rounds, rate, ms: ms + REPEAT_MARGIN_MS });
  }
  return phases;
}

export function describePhase(phase: ShadowPhase): string {
  const where = `Round ${phase.round} of ${phase.rounds}`;
  if (phase.kind === "listen") {
    return phase.rate === 1 ? `${where}: listen at full speed` : `${where}: listen at ${phase.rate}×`;
  }
  return `${where}: your turn — say it now`;
}
