import type {
  ApiErrorResponse, CreateEventDto, DailyBreakdown, DashboardKpis,
  EmployeeView, EmployeeWorkSummary, EventView,
} from "./types";
import { ensureFreshToken, refreshSilently, getAccessToken } from "./auth";
import { getKioskToken } from "./kiosk";

export class ApiError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

type AuthMode = "admin" | "kiosk" | "none";

async function authHeader(mode: AuthMode, retry: boolean): Promise<Record<string, string>> {
  if (mode === "admin") {
    const token = retry ? await ensureFreshToken() : getAccessToken();
    return token ? { Authorization: `Bearer ${token}` } : {};
  }
  if (mode === "kiosk") {
    const token = getKioskToken();
    return token ? { Authorization: `Kiosk ${token}` } : {};
  }
  return {};
}

async function request<T>(path: string, init?: RequestInit, mode: AuthMode = "admin"): Promise<T> {
  const doFetch = async (useFreshToken: boolean) =>
    fetch(path, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...(await authHeader(mode, useFreshToken)),
        ...(init?.headers ?? {}),
      },
    });

  let res = await doFetch(false);

  // Access token expirado: un único intento de refresh silencioso + replay.
  if (res.status === 401 && mode === "admin") {
    const refreshed = await refreshSilently();
    if (refreshed) {
      res = await doFetch(true);
    }
  }

  if (!res.ok) {
    let body: ApiErrorResponse | null = null;
    try {
      body = (await res.json()) as ApiErrorResponse;
    } catch {
      /* sin body */
    }
    throw new ApiError(res.status, body?.code ?? "UNKNOWN", body?.message ?? res.statusText);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  // empleados (admin)
  listEmployees: () => request<EmployeeView[]>("/api/employees"),
  // lookup de tarjeta: lo usa el kiosko con su propio token de dispositivo
  employeeByCard: (uid: string) =>
    request<EmployeeView>(`/api/employees/by-card/${encodeURIComponent(uid)}`, undefined, "kiosk"),

  // eventos
  createEvent: (dto: CreateEventDto) =>
    request<EventView>("/api/events", { method: "POST", body: JSON.stringify(dto) }, "kiosk"),
  listEvents: (params?: { employeeId?: string; from?: string; to?: string; limit?: number }) => {
    const qs = new URLSearchParams();
    if (params?.employeeId) qs.set("employeeId", params.employeeId);
    if (params?.from) qs.set("from", params.from);
    if (params?.to) qs.set("to", params.to);
    if (params?.limit != null) qs.set("limit", String(params.limit));
    return request<EventView[]>(`/api/events${qs.toString() ? "?" + qs.toString() : ""}`);
  },

  // Export CSV requiere Authorization — ya no es un <a href> directo (un
  // link normal no puede mandar headers). Se descarga vía fetch + blob.
  exportCsv: async (params?: { employeeId?: string; fromDate?: string; toDate?: string }) => {
    const qs = new URLSearchParams();
    if (params?.employeeId) qs.set("employeeId", params.employeeId);
    if (params?.fromDate) qs.set("from", params.fromDate);
    if (params?.toDate) qs.set("to", params.toDate);
    const path = `/api/events/export.csv${qs.toString() ? "?" + qs.toString() : ""}`;

    const token = await ensureFreshToken();
    const res = await fetch(path, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!res.ok) {
      throw new ApiError(res.status, "EXPORT_FAILED", "No se pudo exportar el CSV");
    }
    const blob = await res.blob();
    const filename = res.headers.get("Content-Disposition")?.match(/filename="(.+)"/)?.[1] ?? "asistencia.csv";
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  },

  // dashboard
  kpis: () => request<DashboardKpis>("/api/dashboard/kpis"),

  // empleados — agregaciones (params son YYYY-MM-DD MX)
  employeeSummaries: (params?: { fromDate?: string; toDate?: string }) => {
    const qs = new URLSearchParams();
    if (params?.fromDate) qs.set("from", params.fromDate);
    if (params?.toDate) qs.set("to", params.toDate);
    return request<EmployeeWorkSummary[]>(`/api/employees/summaries${qs.toString() ? "?" + qs.toString() : ""}`);
  },
  employeeDaily: (employeeId: string, params?: { fromDate?: string; toDate?: string }) => {
    const qs = new URLSearchParams();
    if (params?.fromDate) qs.set("from", params.fromDate);
    if (params?.toDate) qs.set("to", params.toDate);
    return request<DailyBreakdown[]>(`/api/employees/${employeeId}/daily${qs.toString() ? "?" + qs.toString() : ""}`);
  },

  // demo (requiere JWT tenant_admin, solo perfiles local/demo)
  demoSeed: () => request<{ ok: boolean; eventsGenerated: number }>("/api/demo/seed", { method: "POST" }),
  demoReset: () => request<{ ok: boolean; eventsDeleted: number }>("/api/demo/reset", { method: "POST" }),
};
