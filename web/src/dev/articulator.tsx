/** Entry point of the articulator lab (`npm run dev` → /articulator.html).
 *
 *  Development only: `vite build` bundles index.html and nothing else, so none
 *  of this reaches the interface that gets shipped. With `?grid=…` or `?seq=…`
 *  it shows still frames for screenshots instead of the interactive bench;
 *  with `?perf=1`, the cost of a frame.
 */

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import "../theme/index.css";
import { ArticulatorLab } from "./ArticulatorLab";
import { Perf } from "./Perf";
import { Snapshots, applyQueryTheme } from "./Snapshots";

const root = document.getElementById("root");
if (!root) throw new Error("#root is missing from articulator.html");

const params = new URLSearchParams(window.location.search);
applyQueryTheme(params);
const stills = params.has("grid") || params.has("seq");

createRoot(root).render(
  <StrictMode>
    {params.has("perf") ? <Perf /> : stills ? <Snapshots params={params} /> : <ArticulatorLab />}
  </StrictMode>,
);
