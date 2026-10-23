import { useState } from "react";
import { Calendar } from "lucide-react";
import { cn } from "@/lib/cn";
import {
  type DateRange, presetToday, presetLast7, presetQuincena, presetMonth,
} from "@/lib/dateRange";

export type { DateRange } from "@/lib/dateRange";

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
