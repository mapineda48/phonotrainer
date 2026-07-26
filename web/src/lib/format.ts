/** Formateos compartidos (tiempos siempre con dígitos tabulares en la UI). */

/** 12.34 → "0:12.3" */
export function fmtTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) seconds = 0;
  // Redondear ANTES de partir en minutos: si no, 59.96 s sale como "0:60.0".
  const tenths = Math.round(seconds * 10);
  const min = Math.floor(tenths / 600);
  const rest = (tenths - min * 600) / 10;
  return `${min}:${rest.toFixed(1).padStart(4, "0")}`;
}

/** 128.4 → "2 min 8 s" */
export function fmtDuration(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds)) return "—";
  if (seconds < 60) return `${seconds.toFixed(1)} s`;
  const min = Math.floor(seconds / 60);
  return `${min} min ${Math.round(seconds - min * 60)} s`;
}

/** ISO → "26 jul, 15:40" */
export function fmtDate(iso: string | null): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return date.toLocaleString("es", {
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function fmtBytes(bytes: number | undefined): string {
  if (!bytes) return "";
  const units = ["B", "kB", "MB", "GB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value < 10 && unit > 0 ? 1 : 0)} ${units[unit]}`;
}
