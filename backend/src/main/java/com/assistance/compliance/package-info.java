/**
 * Módulo Compliance — auditoría transversal (LFT Art. 804 / LFPDPPP).
 *
 * NamedInterfaces públicos:
 *   compliance::api — port (AuditApi) para que otros módulos registren
 *   lecturas/escrituras sensibles (export CSV, consulta de datos personales,
 *   correcciones de eventos). Las escrituras de negocio normales (employee,
 *   nfc_card, kiosko, event_correction) se auditan automáticamente vía
 *   trigger {@code write_audit_log()} en Postgres (ver V6__audit_log.sql); no
 *   requieren llamar a este API.
 *
 * Solo depende de {@code shared::api} (TenantContext) para resolver tenant y
 * actor; no conoce las entidades de los otros módulos, por eso es seguro que
 * attendance dependa de compliance sin crear un ciclo.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Compliance",
        allowedDependencies = {"shared::api"}
)
package com.assistance.compliance;
