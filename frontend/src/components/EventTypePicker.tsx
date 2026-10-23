import { LogIn, LogOut, Coffee, Soup } from "lucide-react";
import type { EventType } from "@/lib/types";
import { cn } from "@/lib/cn";

const OPTIONS: Array<{ type: EventType; label: string; Icon: typeof LogIn }> = [
  { type: "CHECK_IN", label: "Entrada", Icon: LogIn },
  { type: "CHECK_OUT", label: "Salida", Icon: LogOut },
  { type: "LUNCH_OUT", label: "Sale a comer", Icon: Coffee },
  { type: "LUNCH_IN", label: "Vuelve de comer", Icon: Soup },
];

export function EventTypePicker({
  selected,
  suggestion,
  onSelect,
}: {
  selected: EventType | null;
  suggestion: EventType;
  onSelect: (t: EventType) => void;
}) {
  return (
    <div className="grid w-full grid-cols-2 gap-3">
      {OPTIONS.map(({ type, label, Icon }) => {
        const isSelected = selected === type;
        const isSuggested = !selected && type === suggestion;
        return (
          <button
            key={type}
            type="button"
            onClick={() => onSelect(type)}
            className={cn(
              "flex flex-col items-center justify-center gap-2 rounded-2xl border px-3 py-4 transition active:scale-95",
              isSelected
                ? "border-primary bg-primary text-primary-foreground shadow-lg"
                : isSuggested
                ? "border-primary/40 bg-primary/10 text-foreground"
                : "border-border bg-card text-card-foreground",
            )}
          >
            <Icon className="h-7 w-7" strokeWidth={isSelected ? 2.4 : 2} />
            <span className="text-sm font-semibold leading-tight">{label}</span>
            {isSuggested && (
              <span className="text-[10px] uppercase tracking-wider opacity-70">
                Sugerido
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
