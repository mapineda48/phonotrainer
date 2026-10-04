/** Set the browser tab title for the current page ("Library · PhonoTrainer"): screen
 *  readers announce it on navigation, and it names the tab. */

import { useEffect } from "react";

export function useDocumentTitle(title: string | null | undefined): void {
  useEffect(() => {
    document.title = title ? `${title} · PhonoTrainer` : "PhonoTrainer";
  }, [title]);
}
