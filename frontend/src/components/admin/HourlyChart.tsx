import { useMemo } from "react";
import {
  Area, AreaChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from "recharts";
import type { EventView } from "@/lib/types";

const MX_TZ = "America/Mexico_City";

function hourInMx(iso: string): number {
  // hora local MX a partir del Instant ISO
  const d = new Date(iso);
  const fmt = new Intl.DateTimeFormat("es-MX", {
    hour: "2-digit", hour12: false, timeZone: MX_TZ,
  });
  return Number(fmt.format(d));
}

export function HourlyChart({ events }: { events: EventView[] }) {
  const data = useMemo(() => {
    const buckets = new Map<number, number>();
    for (let h = 0; h < 24; h++) buckets.set(h, 0);
    for (const e of events) {
      const h = hourInMx(e.eventTime);
      buckets.set(h, (buckets.get(h) ?? 0) + 1);
    }
    return Array.from(buckets.entries())
      .sort((a, b) => a[0] - b[0])
      .map(([h, count]) => ({
        hour: `${String(h).padStart(2, "0")}:00`,
        count,
      }));
  }, [events]);

  return (
    <div className="rounded-2xl border border-border bg-card p-5 shadow-sm">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <div className="text-sm font-medium">Eventos por hora</div>
          <div className="text-xs text-muted-foreground">Distribución del periodo</div>
        </div>
        <div className="text-2xl font-semibold tabular-nums">{events.length}</div>
      </div>
      <div className="h-48">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data} margin={{ top: 5, right: 4, bottom: 0, left: -16 }}>
            <defs>
              <linearGradient id="areaPrimary" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="hsl(217 91% 60%)" stopOpacity={0.6} />
                <stop offset="100%" stopColor="hsl(217 91% 60%)" stopOpacity={0} />
              </linearGradient>
            </defs>
            <XAxis
              dataKey="hour"
              tick={{ fill: "hsl(215 20% 65%)", fontSize: 10 }}
              tickLine={false}
              axisLine={false}
              interval={2}
            />
            <YAxis
              tick={{ fill: "hsl(215 20% 65%)", fontSize: 10 }}
              tickLine={false}
              axisLine={false}
              allowDecimals={false}
              width={28}
            />
            <Tooltip
              cursor={{ stroke: "hsl(215 20% 30%)", strokeWidth: 1 }}
              contentStyle={{
                background: "hsl(222 47% 6%)",
                border: "1px solid hsl(217 33% 17%)",
                borderRadius: 8,
                fontSize: 12,
              }}
              labelStyle={{ color: "hsl(215 20% 65%)" }}
              itemStyle={{ color: "hsl(210 40% 98%)" }}
            />
            <Area
              type="monotone"
              dataKey="count"
              stroke="hsl(217 91% 60%)"
              strokeWidth={2}
              fill="url(#areaPrimary)"
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
