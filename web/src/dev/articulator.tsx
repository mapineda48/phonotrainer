/** Entry point of the articulator lab (`npm run dev` → /articulator.html).
 *
 *  Development only: `vite build` bundles index.html and nothing else, so none
 *  of this reaches the interface that gets shipped.
 */

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import "../styles.css";
import { ArticulatorLab } from "./ArticulatorLab";

const root = document.getElementById("root");
if (!root) throw new Error("#root is missing from articulator.html");

createRoot(root).render(
  <StrictMode>
    <ArticulatorLab />
  </StrictMode>,
);
