/** The frame every page sits in: skip link, the navigation, the tour offer, and the page
 *  in <main>. Also renders, once, the SVG definitions shared by every page (family
 *  hatch patterns, color-vision simulation filters).
 *
 *  From 768 px the navigation is a rail beside the page; below that it is a slim bar
 *  above it (a Menu button and a drawer), so the page keeps the whole width. */

import type { ReactNode } from "react";

import { TourOffer } from "../features/tour";
import { BREAKPOINTS, useMediaQuery } from "../hooks/useMediaQuery";
import { CvdFilterDefs } from "../settings/CvdFilterDefs";
import { cn, FamilyPatternDefs } from "../ui";
import { NavRail } from "./NavRail";

export function AppShell({ children }: { children: ReactNode }) {
  const compact = useMediaQuery(BREAKPOINTS.compactNav);
  return (
    <div
      className={cn(
        "grid h-dvh bg-page text-ink",
        compact ? "grid-cols-1 grid-rows-[auto_minmax(0,1fr)]" : "grid-cols-[auto_minmax(0,1fr)]",
      )}
    >
      <a
        href="#main"
        className="sr-only z-50 rounded-control bg-ink px-3 py-2 text-page focus:not-sr-only focus:fixed focus:left-3 focus:top-3"
      >
        Skip to content
      </a>
      <FamilyPatternDefs />
      <CvdFilterDefs />
      <NavRail compact={compact} />
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
