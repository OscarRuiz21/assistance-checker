import type { LucideIcon } from "lucide-react";
import { cn } from "@/lib/cn";

export function KpiCard({
  label,
  value,
  hint,
  Icon,
  tone = "default",
}: {
  label: string;
  value: string | number;
  hint?: string;
  Icon: LucideIcon;
  tone?: "default" | "warn" | "success";
}) {
  return (
    <div className="rounded-2xl border border-border bg-card p-5 shadow-sm">
      <div className="flex items-start justify-between">
        <div className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
          {label}
        </div>
        <Icon
          className={cn(
            "h-5 w-5",
            tone === "warn" && "text-amber-400",
            tone === "success" && "text-emerald-400",
            tone === "default" && "text-muted-foreground",
          )}
        />
      </div>
      <div className="mt-3 text-3xl font-semibold tabular-nums">{value}</div>
      {hint && <div className="mt-1 text-xs text-muted-foreground">{hint}</div>}
    </div>
  );
}
