import { useState } from "react";
import { Calendar } from "lucide-react";
import { cn } from "@/lib/cn";

export type DateRange = {
  /** YYYY-MM-DD en zona MX */
  from: string;
  to: string;
  /** Etiqueta legible para mostrar en UI */
  label: string;
};

const TZ = "America/Mexico_City";

function ymdInMx(date: Date): string {
  const fmt = new Intl.DateTimeFormat("en-CA", {
    year: "numeric", month: "2-digit", day: "2-digit", timeZone: TZ,
  });
  return fmt.format(date);
}

function nowInMx(): Date {
  // Date "ahora" — JS no permite construir Date en zona específica, pero las
  // operaciones que hago (getDate, getMonth) las haré con un trick formateando.
  return new Date();
}

function dayInMx(date: Date): number {
  return Number(new Intl.DateTimeFormat("en-CA", { day: "numeric", timeZone: TZ }).format(date));
}

function startOfMonthMx(date: Date): Date {
  const ymd = new Intl.DateTimeFormat("en-CA", {
    year: "numeric", month: "2-digit", day: "2-digit", timeZone: TZ,
  }).format(date);
  const [y, m] = ymd.split("-");
  return new Date(`${y}-${m}-01T00:00:00`);
}

function endOfMonthMx(date: Date): Date {
  const start = startOfMonthMx(date);
  // último día del mes: primer día del mes siguiente menos 1 día
  const next = new Date(start);
  next.setMonth(next.getMonth() + 1);
  next.setDate(0); // último día del mes anterior
  return next;
}

function addDays(date: Date, days: number): Date {
  const d = new Date(date);
  d.setDate(d.getDate() + days);
  return d;
}

export function presetToday(): DateRange {
  const now = nowInMx();
  const ymd = ymdInMx(now);
  return { from: ymd, to: ymd, label: "Hoy" };
}

export function presetLast7(): DateRange {
  const now = nowInMx();
  return { from: ymdInMx(addDays(now, -6)), to: ymdInMx(now), label: "Últimos 7 días" };
}

export function presetQuincena(): DateRange {
  const now = nowInMx();
  const day = dayInMx(now);
  const start = startOfMonthMx(now);
  if (day <= 15) {
    const q1End = new Date(start);
    q1End.setDate(15);
    return { from: ymdInMx(start), to: ymdInMx(q1End), label: "Quincena actual (1-15)" };
  } else {
    const q2Start = new Date(start);
    q2Start.setDate(16);
    return { from: ymdInMx(q2Start), to: ymdInMx(endOfMonthMx(now)), label: "Quincena actual (16-fin)" };
  }
}

export function presetMonth(): DateRange {
  const now = nowInMx();
  return {
    from: ymdInMx(startOfMonthMx(now)),
    to: ymdInMx(endOfMonthMx(now)),
    label: "Este mes",
  };
}

const PRESETS: Array<{ key: string; build: () => DateRange }> = [
  { key: "today", build: presetToday },
  { key: "last7", build: presetLast7 },
  { key: "quincena", build: presetQuincena },
  { key: "month", build: presetMonth },
];

export function DateRangePicker({
  value,
  onChange,
}: {
  value: DateRange;
  onChange: (r: DateRange) => void;
}) {
  const [showCustom, setShowCustom] = useState(false);

  function pickPreset(key: string) {
    setShowCustom(false);
    const preset = PRESETS.find((p) => p.key === key)?.build();
    if (preset) onChange(preset);
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      <Calendar className="h-4 w-4 text-muted-foreground" />
      {PRESETS.map(({ key, build }) => {
        const sample = build();
        const isActive = !showCustom && value.from === sample.from && value.to === sample.to;
        return (
          <button
            key={key}
            type="button"
            onClick={() => pickPreset(key)}
            className={cn(
              "rounded-full border px-3 py-1.5 text-xs font-medium transition",
              isActive
                ? "border-primary bg-primary text-primary-foreground"
                : "border-border bg-card text-card-foreground hover:bg-secondary",
            )}
          >
            {sample.label.split("(")[0].trim()}
          </button>
        );
      })}
      <button
        type="button"
        onClick={() => setShowCustom((s) => !s)}
        className={cn(
          "rounded-full border px-3 py-1.5 text-xs font-medium transition",
          showCustom
            ? "border-primary bg-primary text-primary-foreground"
            : "border-border bg-card text-card-foreground hover:bg-secondary",
        )}
      >
        Personalizado
      </button>

      {showCustom && (
        <div className="flex w-full items-center gap-2 pt-2">
          <input
            type="date"
            value={value.from}
            onChange={(e) => onChange({ ...value, from: e.target.value, label: "Personalizado" })}
            className="rounded-md border border-border bg-card px-2 py-1 text-sm"
          />
          <span className="text-xs text-muted-foreground">a</span>
          <input
            type="date"
            value={value.to}
            onChange={(e) => onChange({ ...value, to: e.target.value, label: "Personalizado" })}
            className="rounded-md border border-border bg-card px-2 py-1 text-sm"
          />
        </div>
      )}
    </div>
  );
}

/** Convierte un DateRange (YYYY-MM-DD MX) a Instant ISO para el API. */
export function rangeToIsoInstants(r: DateRange): { from: string; to: string } {
  // Asumimos zona MX. El backend ya re-interpreta usando la zona configurada.
  const fromIso = new Date(`${r.from}T00:00:00-06:00`).toISOString();
  const toIso = new Date(`${r.to}T23:59:59-06:00`).toISOString();
  return { from: fromIso, to: toIso };
}
