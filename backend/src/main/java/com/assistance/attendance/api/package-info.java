/**
 * NamedInterface "api" del módulo Attendance. Contrato síncrono completo:
 *   - AttendanceApi       (port)
 *   - RecordEventCommand  (command — input al API)
 *   - EventView           (read model)
 *   - DashboardKpis       (read model)
 *   - EventType           (value object)
 *   - CaptureMethod       (value object)
 *
 * Los eventos de dominio (asíncronos) viven en {@code attendance.event}, con
 * NamedInterface "event" separado.
 */
@org.springframework.modulith.NamedInterface("api")
package com.assistance.attendance.api;
