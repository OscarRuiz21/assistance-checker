import { useEffect, useState } from "react";
import { cn } from "@/lib/cn";
import type { CaptureMethod, EventType, EventView } from "@/lib/types";

const MX_FMT = new Intl.DateTimeFormat("es-MX", {
  hour: "2-digit", minute: "2-digit", second: "2-digit",
  hour12: false, timeZone: "America/Mexico_City",
});

const TYPE_LABEL: Record<EventType, string> = {
  CHECK_IN: "Entrada",
  CHECK_OUT: "Salida",
  LUNCH_OUT: "Sale comer",
  LUNCH_IN: "Vuelve",
};

const METHOD_LABEL: Record<CaptureMethod, string> = {
  NFC: "NFC",
  PIN: "PIN",
  MANUAL: "Manual",
};

export function EventsTable({
  events,
  highlightId,
}: {
  events: EventView[];
  highlightId: string | null;
}) {
  const [pulseId, setPulseId] = useState<string | null>(null);
  useEffect(() => {
    if (!highlightId) return;
    setPulseId(highlightId);
    const t = setTimeout(() => setPulseId(null), 2200);
    return () => clearTimeout(t);
  }, [highlightId]);

  if (events.length === 0) {
    return (
      <div className="rounded-2xl border border-dashed border-border bg-card/40 py-12 text-center text-sm text-muted-foreground">
        Sin eventos en el periodo. Toca [Sembrar demo] o registra una captura.
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded-2xl border border-border bg-card shadow-sm">
      <table className="w-full text-sm">
        <thead className="border-b border-border bg-muted/30 text-left text-xs uppercase tracking-wider text-muted-foreground">
          <tr>
            <th className="px-4 py-3 font-medium">Empleado</th>
            <th className="px-4 py-3 font-medium">Tipo</th>
            <th className="px-4 py-3 font-medium">Método</th>
            <th className="px-4 py-3 text-right font-medium tabular-nums">Hora</th>
          </tr>
        </thead>
        <tbody>
          {events.map((e) => (
            <tr
              key={e.id}
              className={cn(
                "border-b border-border/50 transition-colors",
                pulseId === e.id && "bg-success/10 animate-fade-in",
              )}
            >
              <td className="px-4 py-3">
                <div className="font-medium">{e.employeeName}</div>
                <div className="text-xs text-muted-foreground">{e.employeeNumber}</div>
              </td>
              <td className="px-4 py-3">
                <span className="rounded-md bg-secondary px-2 py-0.5 text-xs">
                  {TYPE_LABEL[e.eventType]}
                </span>
              </td>
              <td className="px-4 py-3 text-xs text-muted-foreground">
                {METHOD_LABEL[e.captureMethod]}
              </td>
              <td className="px-4 py-3 text-right tabular-nums text-muted-foreground">
                {MX_FMT.format(new Date(e.eventTime))}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
