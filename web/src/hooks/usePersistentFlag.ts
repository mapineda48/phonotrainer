/** Booleano guardado en localStorage: preferencias de la interfaz que deben
 *  sobrevivir a cambios de análisis y a recargas (p. ej. mostrar el video). */

import { useCallback, useState } from "react";

export function usePersistentFlag(key: string, initial = false): [boolean, () => void] {
  const [value, setValue] = useState(() => {
    try {
      const saved = window.localStorage.getItem(key);
      return saved === null ? initial : saved === "1";
    } catch {
      return initial; // almacenamiento bloqueado (modo privado estricto…)
    }
  });

  const toggle = useCallback(() => {
    setValue((current) => {
      const next = !current;
      try {
        window.localStorage.setItem(key, next ? "1" : "0");
      } catch {
        /* sin almacenamiento la preferencia dura lo que dure la pestaña */
      }
      return next;
    });
  }, [key]);

  return [value, toggle];
}
