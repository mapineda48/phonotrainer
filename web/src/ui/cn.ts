/** Class helpers shared by every component: `cn` joins and de-duplicates Tailwind classes
 *  (the last conflicting utility wins), `tv` builds variant recipes. */

import { clsx, type ClassValue } from "clsx";
import { composeRenderProps } from "react-aria-components";
import { twMerge } from "tailwind-merge";

export { tv, type VariantProps } from "tailwind-variants";

export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}

/** For React Aria components whose className may be a function of their render state:
 *  prepends `extra` to whatever the caller passed (the caller's classes win conflicts). */
export function composeClass<T>(
  className: string | ((values: T) => string) | undefined,
  extra: string | ((values: T) => string),
): string | ((values: T) => string) {
  return composeRenderProps(className, (passed, values: T) =>
    cn(typeof extra === "function" ? extra(values) : extra, passed),
  );
}
