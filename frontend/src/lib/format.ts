/**
 * Helpers de formato para tiempos y fechas en el admin.
 */

const TZ_MX = "America/Mexico_City";

const fmtTime = new Intl.DateTimeFormat("es-MX", {
  hour: "2-digit", minute: "2-digit", hour12: false, timeZone: TZ_MX,
});

const fmtDateLong = new Intl.DateTimeFormat("es-MX", {
  weekday: "short", day: "numeric", month: "short", timeZone: TZ_MX,
});

const fmtDateShort = new Intl.DateTimeFormat("es-MX", {
  day: "numeric", month: "short", timeZone: TZ_MX,
});

const fmtDateTime = new Intl.DateTimeFormat("es-MX", {
  day: "numeric", month: "short", hour: "2-digit", minute: "2-digit",
  hour12: false, timeZone: TZ_MX,
});

export function timeMx(iso: string | null): string {
  if (!iso) return "—";
  return fmtTime.format(new Date(iso));
}

export function dateLongMx(d: string | Date): string {
  const date = typeof d === "string" ? new Date(d.includes("T") ? d : d + "T12:00:00") : d;
  return fmtDateLong.format(date);
}

export function dateShortMx(d: string | Date): string {
  const date = typeof d === "string" ? new Date(d.includes("T") ? d : d + "T12:00:00") : d;
  return fmtDateShort.format(date);
}

export function dateTimeMx(iso: string | null): string {
  if (!iso) return "—";
  return fmtDateTime.format(new Date(iso));
}

/** Convierte segundos a "8h 32m" o "0m" */
export function hours(secs: number): string {
  if (!secs || secs <= 0) return "0m";
  const totalMin = Math.round(secs / 60);
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  if (h === 0) return `${m}m`;
  if (m === 0) return `${h}h`;
  return `${h}h ${m}m`;
}

/** "8.5 h" para promedios */
export function hoursDecimal(secs: number): string {
  if (!secs || secs <= 0) return "0.0 h";
  return `${(secs / 3600).toFixed(1)} h`;
}
