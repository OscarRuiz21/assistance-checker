package com.assistance.compliance;

import com.assistance.shared.api.TenantContext;
import com.assistance.shared.api.TenantScope;
import com.assistance.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 3.3: prueba directa de {@code archive_and_purge_events(...)} (la
 * función SQL que usa {@code com.assistance.compliance.internal.service.RetentionService},
 * ver V7__retention_config.sql) sin depender de que el job @Scheduled esté
 * habilitado (app.retention.enabled=false por defecto en todos los
 * perfiles): invoca la función directamente, igual que lo haría el job.
 *
 * Verifica el invariante central: nunca DELETE sin archivar antes.
 */
class RetentionArchivingIntegrationTest extends PostgresIntegrationTestBase {

    @Test
    void archiveAndPurge_copiaAEventRawArchiveAntesDeBorrarDeEventRaw() {
        UUID tenantId = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        UUID kioskoId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID oldEventId = UUID.randomUUID();
        UUID recentEventId = UUID.randomUUID();

        TenantContext.runAs(TenantScope.system(tenantId), () -> {
            jdbc.update("INSERT INTO tenant (id, name, contact_email) VALUES (?::uuid, ?, ?)",
                    tenantId.toString(), "Tenant Retention", "r@example.com");
            jdbc.update("INSERT INTO plant (id, tenant_id, name, code) VALUES (?::uuid, ?::uuid, ?, ?)",
                    plantId.toString(), tenantId.toString(), "Planta 1", "P1");
            jdbc.update("INSERT INTO kiosko (id, tenant_id, plant_id, name, type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'web_kiosko')",
                    kioskoId.toString(), tenantId.toString(), plantId.toString(), "Kiosko 1");
            jdbc.update("INSERT INTO employee (id, tenant_id, employee_number, full_name, "
                            + "default_plant_id, status) VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid, 'active')",
                    employeeId.toString(), tenantId.toString(), "E-1", "Empleado 1", plantId.toString());

            Instant sixYearsAgo = Instant.now().minus(6 * 365L, ChronoUnit.DAYS);
            Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);

            insertEvent(oldEventId, tenantId, plantId, kioskoId, employeeId, sixYearsAgo);
            insertEvent(recentEventId, tenantId, plantId, kioskoId, employeeId, yesterday);
            return null;
        });

        Instant cutoff = Instant.now().minus(5 * 365L, ChronoUnit.DAYS);
        Integer archivedCount = TenantContext.runAs(TenantScope.system(tenantId), () -> jdbc.queryForObject(
                "SELECT archive_and_purge_events(?::uuid, ?)",
                Integer.class, tenantId.toString(), Timestamp.from(cutoff)));

        assertThat(archivedCount).isEqualTo(1);

        TenantContext.runAs(TenantScope.system(tenantId), () -> {
            Long inArchive = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_raw_archive WHERE id = ?::uuid", Long.class, oldEventId.toString());
            Long stillInRaw = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_raw WHERE id = ?::uuid", Long.class, oldEventId.toString());
            Long recentUntouched = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_raw WHERE id = ?::uuid", Long.class, recentEventId.toString());

            assertThat(inArchive).isEqualTo(1L);
            assertThat(stillInRaw).isEqualTo(0L);
            assertThat(recentUntouched).isEqualTo(1L);
            return null;
        });
    }

    @Test
    void listTenantIdsForRetention_devuelveTenantsDeTodosLosTenantsSinImportarElContextoActual() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.runAs(TenantScope.system(tenantId), () -> jdbc.update(
                "INSERT INTO tenant (id, name, contact_email, retention_config) "
                        + "VALUES (?::uuid, ?, ?, '{\"events_years\":3,\"audit_years\":7}'::jsonb)",
                tenantId.toString(), "Tenant Config", "c@example.com"));

        // Sin tenant en contexto: la función en sí es SECURITY DEFINER con
        // dueño superusuario, por diseño ve todos los tenants (es la única
        // vía deliberada de cruzar tenants, ver V7).
        TenantContext.clear();
        var rows = jdbc.queryForList(
                "SELECT * FROM list_tenant_ids_for_retention() WHERE tenant_id = ?::uuid", tenantId.toString());

        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("events_years")).intValue()).isEqualTo(3);
    }

    private void insertEvent(UUID id, UUID tenantId, UUID plantId, UUID kioskoId, UUID employeeId, Instant eventTime) {
        jdbc.update("INSERT INTO event_raw (id, tenant_id, plant_id, kiosko_id, employee_id, "
                        + "event_type, capture_method, device_time, event_time, is_valid) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'check_in', 'nfc', ?, ?, true)",
                id.toString(), tenantId.toString(), plantId.toString(), kioskoId.toString(), employeeId.toString(),
                Timestamp.from(eventTime), Timestamp.from(eventTime));
    }
}
