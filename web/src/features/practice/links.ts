/** Practice deep links. `focus` restricts a session to one phenomenon
 *  (/practice?focus=flapping); Practice reads it with wouter's useSearch. */

import { paths } from "../../paths";

export const FOCUS_PARAM = "focus";

export const practiceHref = (focus?: string | null): string => paths.practice(focus);
