import type {
  ApiErrorResponse, CreateEventDto, DailyBreakdown, DashboardKpis,
  EmployeeView, EmployeeWorkSummary, EventView,
} from "./types";

export class ApiError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
    ...init,
  });
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
  // empleados
  listEmployees: () => request<EmployeeView[]>("/api/employees"),
  employeeByCard: (uid: string) =>
    request<EmployeeView>(`/api/employees/by-card/${encodeURIComponent(uid)}`),

  // eventos
  createEvent: (dto: CreateEventDto) =>
    request<EventView>("/api/events", { method: "POST", body: JSON.stringify(dto) }),
  listEvents: (params?: { employeeId?: string; from?: string; to?: string; limit?: number }) => {
    const qs = new URLSearchParams();
    if (params?.employeeId) qs.set("employeeId", params.employeeId);
    if (params?.from) qs.set("from", params.from);
    if (params?.to) qs.set("to", params.to);
    if (params?.limit != null) qs.set("limit", String(params.limit));
    return request<EventView[]>(`/api/events${qs.toString() ? "?" + qs.toString() : ""}`);
  },

  // URL para descargar CSV (no fetch — el browser hace download directo)
  exportCsvUrl: (params?: { employeeId?: string; fromDate?: string; toDate?: string }) => {
    const qs = new URLSearchParams();
    if (params?.employeeId) qs.set("employeeId", params.employeeId);
    if (params?.fromDate) qs.set("from", params.fromDate);
    if (params?.toDate) qs.set("to", params.toDate);
    return `/api/events/export.csv${qs.toString() ? "?" + qs.toString() : ""}`;
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

  // demo
  demoSeed: () => request<{ ok: boolean; eventsGenerated: number }>("/api/demo/seed", { method: "POST" }),
  demoReset: () => request<{ ok: boolean; eventsDeleted: number }>("/api/demo/reset", { method: "POST" }),
};
