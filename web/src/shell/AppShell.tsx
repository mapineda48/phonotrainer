/** The frame every page sits in: skip link, navigation rail, the tour offer, and the
 *  page in <main>. Also renders, once, the SVG definitions shared by every page (family
 *  hatch patterns, color-vision simulation filters). */

import type { ReactNode } from "react";

import { TourOffer } from "../features/tour";
import { CvdFilterDefs } from "../settings/CvdFilterDefs";
import { FamilyPatternDefs } from "../ui";
import { NavRail } from "./NavRail";

export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="grid h-dvh grid-cols-[auto_minmax(0,1fr)] bg-page text-ink">
      <a
        href="#main"
        className="sr-only z-50 rounded-control bg-ink px-3 py-2 text-page focus:not-sr-only focus:fixed focus:left-3 focus:top-3"
      >
        Skip to content
      </a>
      <FamilyPatternDefs />
      <CvdFilterDefs />
      <NavRail />
      <div className="flex min-h-0 min-w-0 flex-col">
        <TourOffer />
        {/* relative: absolutely positioned .sr-only descendants must stay inside this
            scroll container, or they grow the document and scrollIntoView moves the shell */}
        <main id="main" tabIndex={-1} className="relative min-h-0 flex-1 overflow-auto outline-none">
          {children}
        </main>
      </div>
    </div>
  );
}
