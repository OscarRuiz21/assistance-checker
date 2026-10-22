import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { ChevronRight, Loader2, Search, Users } from "lucide-react";
import { ApiError, api } from "@/lib/api";
import { useEventStream } from "@/lib/sse";
import { dateTimeMx, hours, hoursDecimal } from "@/lib/format";
import type { EmployeeWorkSummary } from "@/lib/types";
import { AdminShell } from "@/routes/admin/AdminShell";
import { AvatarInitials } from "@/components/AvatarInitials";
import {
  DateRangePicker, presetQuincena, type DateRange,
} from "@/components/admin/DateRangePicker";
import { cn } from "@/lib/cn";

export default function Employees() {
  const [range, setRange] = useState<DateRange>(presetQuincena);
  const [summaries, setSummaries] = useState<EmployeeWorkSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [query, setQuery] = useState("");

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const data = await api.employeeSummaries({ fromDate: range.from, toDate: range.to });
      setSummaries(data);
      setErrorMsg(null);
    } catch (e) {
      setErrorMsg(e instanceof ApiError ? e.message : "Error al cargar empleados");
    } finally {
      setLoading(false);
    }
  }, [range]);

  useEffect(() => { void refresh(); }, [refresh]);

  // En vivo: cuando llega un evento, refrescar las sumas
  const sseStatus = useEventStream<unknown>("/api/events/stream", "attendance", () => {
    void refresh();
  });

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return summaries;
    return summaries.filter((s) =>
      s.employeeName.toLowerCase().includes(q) ||
      s.employeeNumber.toLowerCase().includes(q),
    );
  }, [summaries, query]);

  const totals = useMemo(() => {
    const active = summaries.filter((s) => s.daysWorked > 0);
    const totalSecsWorked = active.reduce((sum, s) => sum + s.secondsWorked, 0);
    return {
      totalEmployees: summaries.length,
      activeEmployees: active.length,
      totalSecsWorked,
    };
  }, [summaries]);

  return (
    <AdminShell sseStatus={sseStatus}>
      <div className="mb-6 flex items-end justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <Users className="h-5 w-5 text-primary" />
            <h2 className="text-2xl font-semibold">Empleados</h2>
          </div>
          <div className="mt-1 text-sm text-muted-foreground">
            {loading
              ? "Cargando…"
              : `${totals.activeEmployees} activos en periodo · ${totals.totalEmployees} totales · ${hoursDecimal(totals.totalSecsWorked)} trabajadas`}
          </div>
        </div>
        <div className="relative w-72">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Buscar por nombre o número"
            className="w-full rounded-xl border border-border bg-card py-2 pl-9 pr-3 text-sm focus:border-primary focus:outline-none"
          />
        </div>
      </div>

      <div className="mb-4">
        <DateRangePicker value={range} onChange={setRange} />
        <div className="mt-1.5 text-xs text-muted-foreground">
          {range.label} · {range.from} a {range.to}
        </div>
      </div>

      {errorMsg && (
        <div className="mb-4 rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {errorMsg}
        </div>
      )}

      <div className="overflow-hidden rounded-2xl border border-border bg-card shadow-sm">
        <table className="w-full text-sm">
          <thead className="border-b border-border bg-muted/30 text-left text-xs uppercase tracking-wider text-muted-foreground">
            <tr>
              <th className="px-4 py-3 font-medium">Empleado</th>
              <th className="px-4 py-3 text-right font-medium">Días</th>
              <th className="px-4 py-3 text-right font-medium">Horas trabajadas</th>
              <th className="px-4 py-3 text-right font-medium">Promedio/día</th>
              <th className="px-4 py-3 font-medium">Última entrada</th>
              <th className="px-4 py-3"></th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((s) => {
              const avg = s.daysWorked > 0 ? s.secondsWorked / s.daysWorked : 0;
              const inactive = s.daysWorked === 0;
              return (
                <tr
                  key={s.employeeId}
                  className={cn(
                    "border-b border-border/50 transition-colors hover:bg-secondary/30",
                    inactive && "opacity-50",
                  )}
                >
                  <td className="px-4 py-3">
                    <Link
                      to={`/admin/empleados/${s.employeeId}`}
                      className="flex items-center gap-3 hover:text-primary"
                    >
                      <AvatarInitials name={s.employeeName} size="md" />
                      <div className="min-w-0">
                        <div className="truncate font-medium">{s.employeeName}</div>
                        <div className="text-xs text-muted-foreground">{s.employeeNumber}</div>
                      </div>
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums">
                    {inactive ? (
                      <span className="text-xs text-muted-foreground">sin movimiento</span>
                    ) : (
                      <span className="font-medium">{s.daysWorked}</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums font-medium">
                    {hours(s.secondsWorked)}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-muted-foreground">
                    {avg > 0 ? hours(avg) : "—"}
                  </td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">
                    {dateTimeMx(s.lastCheckIn)}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <Link
                      to={`/admin/empleados/${s.employeeId}`}
                      className="inline-flex items-center text-muted-foreground hover:text-primary"
                    >
                      <ChevronRight className="h-4 w-4" />
                    </Link>
                  </td>
                </tr>
              );
            })}
            {filtered.length === 0 && !loading && (
              <tr>
                <td colSpan={6} className="px-4 py-12 text-center text-sm text-muted-foreground">
                  Sin empleados que coincidan.
                </td>
              </tr>
            )}
            {loading && filtered.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-12 text-center">
                  <Loader2 className="mx-auto h-5 w-5 animate-spin text-muted-foreground" />
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </AdminShell>
  );
}
