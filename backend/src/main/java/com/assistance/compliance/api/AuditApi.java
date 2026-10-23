package com.assistance.compliance.api;

import java.util.Map;
import java.util.UUID;

/**
 * Registro explícito de acciones sensibles que no quedan cubiertas por el
 * trigger {@code write_audit_log()} de Postgres (el cual solo cubre
 * INSERT/UPDATE/DELETE sobre tablas de negocio). Usar para:
 * <ul>
 *   <li>Exportaciones de datos (CSV, reportes).</li>
 *   <li>Consultas puntuales de datos personales fuera del flujo normal de
 *       registro de asistencia.</li>
 *   <li>Cualquier acción que un auditor/DPO necesite poder reconstruir y que
 *       no sea una simple mutación de fila.</li>
 * </ul>
 *
 * Requiere {@link com.assistance.shared.api.TenantContext#require()}: si no
 * hay tenant en el hilo actual, falla (no hay forma de auditar sin saber de
 * qué tenant es la acción).
 */
public interface AuditApi {

    /**
     * @param action     identificador corto de la acción, p. ej.
     *                   {@code "event.export_csv"}, {@code "employee.read_pii"}.
     * @param entityType tabla/entidad lógica afectada, p. ej. {@code "event_raw"}.
     * @param entityId   id de la entidad si aplica a una sola fila; null si es
     *                   una operación masiva (p. ej. un export de muchos
     *                   registros).
     * @param details    metadata adicional serializable a JSON (rango de
     *                   fechas exportado, cantidad de filas, filtros
     *                   aplicados). NUNCA incluir PII en claro aquí.
     */
    void record(String action, String entityType, UUID entityId, Map<String, Object> details);
}
