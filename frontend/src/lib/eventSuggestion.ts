import type { EventType } from "@/lib/types";

/**
 * Sugerencia por hora MX. El usuario PUEDE cambiarla — solo es highlight visual.
 */
export function suggestEventType(): EventType {
  const fmt = new Intl.DateTimeFormat("es-MX", {
    hour: "2-digit", hour12: false, timeZone: "America/Mexico_City",
  });
  const h = Number(fmt.format(new Date()));
  if (h < 12) return "CHECK_IN";
  if (h < 14) return "LUNCH_OUT";
  if (h < 15) return "LUNCH_IN";
  return "CHECK_OUT";
}
