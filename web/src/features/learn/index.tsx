/** Learn: phenomenon cards, phenomenon pages, IPA chart.
 *
 *  Contract with src/routes.tsx: keep these export names and props.
 *    LearnHomePage            →  /learn
 *    IpaChartPage             →  /learn/ipa[?symbol=…]
 *    PhenomenonPage({name})   →  /learn/:phenomenon */

import { IpaChart } from "./IpaChart";
import { LearnHome } from "./LearnHome";
import { PhenomenonPage as Phenomenon } from "./PhenomenonPage";

export function LearnHomePage() {
  return <LearnHome />;
}

export function IpaChartPage() {
  return <IpaChart />;
}

export function PhenomenonPage({ name }: { name: string }) {
  return <Phenomenon name={name} />;
}
