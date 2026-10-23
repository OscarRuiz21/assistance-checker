package com.assistance.attendance.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AttendanceApi {

    EventView record(RecordEventCommand cmd);

    /**
     * Lista eventos en rango de tiempo.
     * @param employeeId si no es null, filtra solo a ese empleado.
     */
    List<EventView> list(UUID employeeId, Instant from, Instant to, int limit);

    /**
     * Igual que {@link #list}, pero además registra en {@code audit_log}
     * (vía AuditApi) que se exportaron estos datos — para usar en el
     * endpoint de export CSV (LFPDPPP: toda lectura masiva de PII debe
     * quedar auditada). AGENTE A: el controller de export.csv debe llamar
     * este método en vez de {@link #list}.
     */
    List<EventView> listForExport(UUID employeeId, Instant from, Instant to, int limit);

    DashboardKpis dashboard(Instant from, Instant to);

    /**
     * Resumen agregado por empleado en el periodo. Incluye empleados sin
     * actividad (con ceros) para que el listado sea completo.
     */
    List<EmployeeWorkSummary> summarize(Instant from, Instant to);

    /**
     * Desglose día a día de un empleado en el periodo. Solo regresa días con
     * al menos un evento; los días vacíos no aparecen.
     */
    List<DailyBreakdown> dailyBreakdown(UUID employeeId, Instant from, Instant to);
}
