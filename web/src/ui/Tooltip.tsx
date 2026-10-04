/** Hover/focus tooltip. Only ever a SUPPLEMENT (the name of an icon button, a shortcut):
 *  explanations go in <Explain>, which keyboard and touch users can open. */

import type { ReactElement, ReactNode } from "react";
import { Tooltip as AriaTooltip, TooltipTrigger } from "react-aria-components";

interface TipProps {
  content: ReactNode;
  /** A single focusable React Aria element (Button, Link, ToggleButton…). */
  children: ReactElement;
  placement?: "top" | "bottom" | "start" | "end";
  delay?: number;
}

export function Tip({ content, children, placement = "top", delay = 500 }: TipProps) {
  return (
    <TooltipTrigger delay={delay} closeDelay={100}>
      {children}
      <AriaTooltip
        placement={placement}
        offset={6}
        className="max-w-xs rounded-control bg-ink px-2.5 py-1.5 text-xs text-page shadow-2"
      >
        {content}
      </AriaTooltip>
    </TooltipTrigger>
  );
}
