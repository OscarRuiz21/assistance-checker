import { useCallback, useEffect, useState } from "react";
import { AlertTriangle, Clock4, Download, RefreshCw, Sparkles, Trash2, Users } from "lucide-react";
import { ApiError, api } from "@/lib/api";
import { useEventStream } from "@/lib/sse";
import type { DashboardKpis, EventView } from "@/lib/types";
import { AdminShell } from "@/routes/admin/AdminShell";
import { KpiCard } from "@/components/admin/KpiCard";
import { EventsTable } from "@/components/admin/EventsTable";
import { HourlyChart } from "@/components/admin/HourlyChart";

const POLL_INTERVAL_MS = 60_000;
const MAX_EVENTS = 50;

export default function Dashboard() {
  const [kpis, setKpis] = useState<DashboardKpis | null>(null);
  const [events, setEvents] = useState<EventView[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [highlightId, setHighlightId] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const [k, ev] = await Promise.all([api.kpis(), api.listEvents({ limit: MAX_EVENTS })]);
      setKpis(k);
      setEvents(ev);
      setErrorMsg(null);
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : "Sin conexión con el backend";
      setErrorMsg(msg);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // Wrapper local (no una referencia directa a `refresh` en el cuerpo del
    // efecto): el fetch inicial + polling es "sincronizar con un sistema
    // externo" (el backend), el caso de uso que los efectos sí deben cubrir.
    const tick = () => void refresh();
    tick();
    const id = setInterval(tick, POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, [refresh]);

  const sseStatus = useEventStream<EventView>(
    "/api/events/stream",
    "attendance",
    (e) => {
      setEvents((prev) => {
        if (prev.find((p) => p.id === e.id)) return prev;
        return [e, ...prev].slice(0, MAX_EVENTS);
      });
      setHighlightId(e.id);
      void refresh(); // KPIs frescos después del nuevo evento
    },
  );

  async function exportCsv() {
    setExporting(true);
    try {
      await api.exportCsv();
    } catch (e) {
      setErrorMsg(e instanceof ApiError ? e.message : "No se pudo exportar el CSV");
    } finally {
      setExporting(false);
    }
  }

  async function seed() {
    setBusy(true);
    try {
      await api.demoSeed();
      await refresh();
    } finally {
      setBusy(false);
    }
  }

  async function reset() {
    if (!confirm("¿Borrar todos los eventos del demo?")) return;
    setBusy(true);
    try {
      await api.demoReset();
      await refresh();
    } finally {
      setBusy(false);
    }
  }

  return (
    <AdminShell sseStatus={sseStatus}>
      {errorMsg && (
        <div className="mb-4 rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {errorMsg}
        </div>
      )}

      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <KpiCard
          label="Entradas hoy"
          value={loading ? "…" : kpis?.totalEntries ?? 0}
          Icon={Sparkles}
          tone="success"
        />
        <KpiCard
          label="Empleados únicos"
          value={loading ? "…" : kpis?.uniqueEmployees ?? 0}
          Icon={Users}
        />
        <KpiCard
          label="Retardos"
          value={loading ? "…" : kpis?.lateCount ?? 0}
          Icon={Clock4}
          tone="warn"
          hint="Entrada después de 8:00"
        />
        <KpiCard
          label="Anomalías"
          value={0}
          Icon={AlertTriangle}
          hint="Sin pendientes"
        />
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <div className="mb-3 flex items-center justify-between">
            <div>
              <div className="text-sm font-medium">Eventos recientes</div>
              <div className="text-xs text-muted-foreground">
                Últimos {events.length} · actualiza en vivo via SSE
              </div>
            </div>
            <div className="flex items-center gap-2">
              <button
                onClick={exportCsv}
                disabled={exporting}
                className="touch-target inline-flex items-center gap-2 rounded-xl bg-secondary px-3 py-2 text-sm font-medium hover:bg-secondary/80 disabled:opacity-50"
                title="Exporta los últimos 30 días"
              >
                <Download className={`h-4 w-4 ${exporting ? "animate-pulse" : ""}`} /> CSV
              </button>
              <button
                onClick={seed}
                disabled={busy}
                className="touch-target inline-flex items-center gap-2 rounded-xl bg-primary px-3 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
              >
                <RefreshCw className={`h-4 w-4 ${busy ? "animate-spin" : ""}`} /> Sembrar demo
              </button>
              <button
                onClick={reset}
                disabled={busy}
                className="touch-target inline-flex items-center gap-2 rounded-xl border border-border px-3 py-2 text-sm hover:bg-secondary/50 disabled:opacity-50"
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </div>
          </div>
          <EventsTable events={events} highlightId={highlightId} />
        </div>

        <div>
          <HourlyChart events={events} />
        </div>
      </div>
    </AdminShell>
  );
}
