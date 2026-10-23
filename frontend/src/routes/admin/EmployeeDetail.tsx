import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, ChevronDown, Coffee, Download, LogIn, LogOut, Soup, Loader2 } from "lucide-react";
import { ApiError, api } from "@/lib/api";
import type { DailyBreakdown, EmployeeView } from "@/lib/types";
import { useEventStream } from "@/lib/sse";
import { dateLongMx, dateTimeMx, hours, hoursDecimal, timeMx } from "@/lib/format";
import { AdminShell } from "@/routes/admin/AdminShell";
import { AvatarInitials } from "@/components/AvatarInitials";
import { DateRangePicker } from "@/components/admin/DateRangePicker";
import { presetQuincena, type DateRange } from "@/lib/dateRange";
import { cn } from "@/lib/cn";

export default function EmployeeDetail() {
  const { id } = useParams<{ id: string }>();
  const [employee, setEmployee] = useState<EmployeeView | null>(null);
  const [breakdown, setBreakdown] = useState<DailyBreakdown[]>([]);
  const [loading, setLoading] = useState(true);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [range, setRange] = useState<DateRange>(presetQuincena);

  useEffect(() => {
    if (!id) return;
    api.listEmployees()
      .then((all) => {
        const found = all.find((e) => e.id === id);
        if (!found) setErrorMsg("Empleado no encontrado");
        else setEmployee(found);
      })
      .catch((e: ApiError) => setErrorMsg(e.message));
  }, [id]);

  const fetchDaily = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const data = await api.employeeDaily(id, { fromDate: range.from, toDate: range.to });
      setBreakdown(data);
      setErrorMsg(null);
    } catch (e) {
      setErrorMsg(e instanceof ApiError ? e.message : "Error al cargar el detalle");
    } finally {
      setLoading(false);
    }
  }, [id, range]);

  useEffect(() => {
    const tick = () => void fetchDaily();
    tick();
  }, [fetchDaily]);

  const sseStatus = useEventStream<{ employeeId: string }>(
    "/api/events/stream",
    "attendance",
    (e) => {
      if (e.employeeId === id) void fetchDaily();
    },
  );

  const stats = useMemo(() => {
    const days = breakdown.length;
    const completeDays = breakdown.filter((d) => d.isComplete).length;
    const totalWorked = breakdown.reduce((s, d) => s + d.secondsWorked, 0);
    const totalLunch = breakdown.reduce((s, d) => s + d.secondsLunch, 0);
    const avgWorked = completeDays > 0 ? totalWorked / completeDays : 0;
    return { days, completeDays, totalWorked, totalLunch, avgWorked };
  }, [breakdown]);

  const [exporting, setExporting] = useState(false);

  const handleExportCsv = useCallback(async () => {
    setExporting(true);
    try {
      await api.exportCsv({ employeeId: id, fromDate: range.from, toDate: range.to });
    } catch (e) {
      setErrorMsg(e instanceof ApiError ? e.message : "No se pudo exportar el CSV");
    } finally {
      setExporting(false);
    }
  }, [id, range]);

  return (
    <AdminShell sseStatus={sseStatus}>
      <div className="mb-4">
        <Link
          to="/admin/empleados"
          className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="h-4 w-4" /> Volver a empleados
        </Link>
      </div>

      {errorMsg && !employee && (
        <div className="rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {errorMsg}
        </div>
      )}

      {employee && (
        <>
          <div className="mb-4 flex items-center gap-4 rounded-2xl border border-border bg-card p-5">
            <AvatarInitials name={employee.fullName} size="xl" />
            <div className="flex-1">
              <div className="text-xl font-semibold">{employee.fullName}</div>
              <div className="text-sm text-muted-foreground">
                {employee.employeeNumber} · {employee.status}
              </div>
            </div>
          </div>

          <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4">
            <KpiTile label="Días con actividad" value={String(stats.days)} />
            <KpiTile label="Días completos" value={String(stats.completeDays)} hint="entrada + salida" />
            <KpiTile label="Horas trabajadas" value={hoursDecimal(stats.totalWorked)} />
            <KpiTile label="Promedio/día" value={hours(stats.avgWorked)} />
          </div>

          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <DateRangePicker value={range} onChange={setRange} />
            <button
              type="button"
              onClick={() => void handleExportCsv()}
              disabled={exporting}
              className="inline-flex items-center gap-2 rounded-xl bg-secondary px-3 py-2 text-sm font-medium hover:bg-secondary/80 disabled:opacity-60"
            >
              {exporting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" />}
              Exportar CSV
            </button>
          </div>

          <div className="mb-2 flex items-center justify-between text-xs text-muted-foreground">
            <div>{range.label} · {range.from} a {range.to}</div>
            <div>
              {loading ? <Loader2 className="h-3 w-3 animate-spin" /> : `${breakdown.length} días`}
            </div>
          </div>

          <DailyTable breakdown={breakdown} loading={loading} />
        </>
      )}
    </AdminShell>
  );
}

function KpiTile({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-2xl border border-border bg-card p-4">
      <div className="text-xs uppercase tracking-wider text-muted-foreground">{label}</div>
      <div className="mt-1 text-2xl font-semibold tabular-nums">{value}</div>
      {hint && <div className="mt-0.5 text-xs text-muted-foreground">{hint}</div>}
    </div>
  );
}

function DailyTable({ breakdown, loading }: { breakdown: DailyBreakdown[]; loading: boolean }) {
  const [expanded, setExpanded] = useState<string | null>(null);

  if (loading && breakdown.length === 0) {
    return (
      <div className="rounded-2xl border border-dashed border-border bg-card/40 py-12 text-center">
        <Loader2 className="mx-auto h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    );
  }
  if (breakdown.length === 0) {
    return (
      <div className="rounded-2xl border border-dashed border-border bg-card/40 py-12 text-center text-sm text-muted-foreground">
        Sin actividad en el periodo seleccionado.
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded-2xl border border-border bg-card shadow-sm">
      <table className="w-full text-sm">
        <thead className="border-b border-border bg-muted/30 text-left text-xs uppercase tracking-wider text-muted-foreground">
          <tr>
            <th className="px-4 py-3 font-medium">Día</th>
            <th className="px-3 py-3 text-center font-medium">Entrada</th>
            <th className="px-3 py-3 text-center font-medium">Sale a comer</th>
            <th className="px-3 py-3 text-center font-medium">Vuelve</th>
            <th className="px-3 py-3 text-center font-medium">Salida</th>
            <th className="px-3 py-3 text-right font-medium">Trabajado</th>
            <th className="px-3 py-3 text-right font-medium">Comida</th>
            <th className="w-8"></th>
          </tr>
        </thead>
        <tbody>
          {breakdown.map((d) => {
            const isExpanded = expanded === d.businessDate;
            return (
              <DailyRow
                key={d.businessDate}
                d={d}
                isExpanded={isExpanded}
                onToggle={() => setExpanded(isExpanded ? null : d.businessDate)}
              />
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function DailyRow({
  d, isExpanded, onToggle,
}: {
  d: DailyBreakdown;
  isExpanded: boolean;
  onToggle: () => void;
}) {
  return (
    <>
      <tr
        onClick={onToggle}
        className={cn(
          "cursor-pointer border-b border-border/50 transition-colors hover:bg-secondary/30",
          !d.isComplete && "bg-amber-500/5",
        )}
      >
        <td className="px-4 py-3">
          <div className="font-medium capitalize">{dateLongMx(d.businessDate)}</div>
          {!d.isComplete && (
            <div className="text-xs text-amber-400">Día incompleto</div>
          )}
        </td>
        <TimeCell iso={d.checkIn} icon={LogIn} />
        <TimeCell iso={d.lunchOut} icon={Coffee} muted />
        <TimeCell iso={d.lunchIn} icon={Soup} muted />
        <TimeCell iso={d.checkOut} icon={LogOut} />
        <td className="px-3 py-3 text-right tabular-nums font-semibold">{hours(d.secondsWorked)}</td>
        <td className="px-3 py-3 text-right tabular-nums text-muted-foreground">{hours(d.secondsLunch)}</td>
        <td className="px-3 py-3">
          <ChevronDown
            className={cn(
              "h-4 w-4 text-muted-foreground transition-transform",
              isExpanded && "rotate-180",
            )}
          />
        </td>
      </tr>
      {isExpanded && (
        <tr className="border-b border-border/50 bg-muted/10">
          <td colSpan={8} className="px-6 py-4">
            <div className="grid grid-cols-2 gap-4 text-sm md:grid-cols-4">
              <DetailField label="Entrada" iso={d.checkIn} />
              <DetailField label="Sale a comer" iso={d.lunchOut} />
              <DetailField label="Vuelve de comer" iso={d.lunchIn} />
              <DetailField label="Salida" iso={d.checkOut} />
            </div>
            <div className="mt-3 text-xs text-muted-foreground">
              En oficina: {hours(d.secondsInOffice)} · Comida: {hours(d.secondsLunch)} ·
              Trabajado neto: <span className="font-semibold text-foreground">{hours(d.secondsWorked)}</span>
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

function TimeCell({ iso, icon: Icon, muted }: { iso: string | null; icon: typeof LogIn; muted?: boolean }) {
  if (!iso) {
    return <td className="px-3 py-3 text-center text-xs text-muted-foreground/50">—</td>;
  }
  return (
    <td className="px-3 py-3 text-center">
      <div className={cn(
        "inline-flex items-center gap-1 tabular-nums",
        muted && "text-muted-foreground",
      )}>
        <Icon className="h-3.5 w-3.5" />
        {timeMx(iso)}
      </div>
    </td>
  );
}

function DetailField({ label, iso }: { label: string; iso: string | null }) {
  return (
    <div>
      <div className="text-xs uppercase tracking-wider text-muted-foreground">{label}</div>
      <div className="mt-0.5 text-sm tabular-nums">{iso ? dateTimeMx(iso) : "—"}</div>
    </div>
  );
}
