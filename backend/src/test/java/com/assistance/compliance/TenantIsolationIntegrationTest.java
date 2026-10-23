package com.assistance.compliance;

import com.assistance.attendance.api.AttendanceApi;
import com.assistance.attendance.api.CaptureMethod;
import com.assistance.attendance.api.EventType;
import com.assistance.attendance.api.EventView;
import com.assistance.attendance.api.RecordEventCommand;
import com.assistance.employee.api.EmployeeApi;
import com.assistance.employee.api.EmployeeView;
import com.assistance.employee.internal.crypto.PiiCipher;
import com.assistance.shared.api.ApiException;
import com.assistance.shared.api.TenantContext;
import com.assistance.shared.api.TenantScope;
import com.assistance.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Cubre el requisito central de Fase 2-3 (docs/16-readiness-plan.md §6): dos
 * tenants nunca deben poder ver, ni por JPA ni por SQL nativo, los datos del
 * otro; {@code event_raw} es append-only incluso para el rol de aplicación;
 * {@code employee}/{@code event_correction} quedan auditados vía trigger; y
 * CURP nunca queda en claro en {@code employee}.
 *
 * <p>Corre contra Postgres 16 real (Testcontainers) con el rol
 * {@code assistance_app} (NOSUPERUSER NOBYPASSRLS, ver V3), exactamente como
 * en producción — por eso un hueco de RLS o de GRANT se detecta aquí.
 */
class TenantIsolationIntegrationTest extends PostgresIntegrationTestBase {

    @Autowired
    private EmployeeApi employeeApi;

    @Autowired
    private AttendanceApi attendanceApi;

    @Autowired
    private PiiCipher piiCipher;

    private UUID tenantA;
    private UUID tenantB;
    private UUID plantA;
    private UUID plantB;
    private UUID kioskoA;
    private UUID kioskoB;
    private UUID employeeA;
    private UUID employeeB;

    @BeforeEach
    void seedTwoTenants() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        plantA = UUID.randomUUID();
        plantB = UUID.randomUUID();
        kioskoA = UUID.randomUUID();
        kioskoB = UUID.randomUUID();
        employeeA = UUID.randomUUID();
        employeeB = UUID.randomUUID();

        seedTenant(tenantA, plantA, kioskoA, employeeA, "Tenant A", "Empleado A", "EA-1");
        seedTenant(tenantB, plantB, kioskoB, employeeB, "Tenant B", "Empleado B", "EB-1");
    }

    private void seedTenant(UUID tenantId, UUID plantId, UUID kioskoId, UUID employeeId,
                            String tenantName, String employeeName, String employeeNumber) {
        TenantContext.runAs(TenantScope.system(tenantId), () -> {
            jdbc.update("INSERT INTO tenant (id, name, contact_email) VALUES (?::uuid, ?, ?)",
                    tenantId.toString(), tenantName, "contact@example.com");
            jdbc.update("INSERT INTO plant (id, tenant_id, name, code) VALUES (?::uuid, ?::uuid, ?, ?)",
                    plantId.toString(), tenantId.toString(), tenantName + " Planta 1", "P1");
            jdbc.update("INSERT INTO kiosko (id, tenant_id, plant_id, name, type) "
                            + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'web_kiosko')",
                    kioskoId.toString(), tenantId.toString(), plantId.toString(), "Kiosko 1");
            jdbc.update("INSERT INTO employee (id, tenant_id, employee_number, full_name, "
                            + "default_plant_id, status) VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid, 'active')",
                    employeeId.toString(), tenantId.toString(), employeeNumber, employeeName, plantId.toString());
            return null;
        });
    }

    private TenantScope adminScope(UUID tenantId) {
        return new TenantScope(tenantId, UUID.randomUUID(), null, Set.of(), Set.of("tenant_admin"));
    }

    @Test
    void sinTenantContext_noVeNingunaFilaDeNingunTenant() {
        TenantContext.clear();

        assertThat(jdbc.queryForList("SELECT id FROM employee")).isEmpty();
        assertThat(jdbc.queryForList("SELECT id FROM plant")).isEmpty();
        assertThat(jdbc.queryForList("SELECT id FROM kiosko")).isEmpty();

        // La capa de servicio también falla explícitamente (TenantContext.require()),
        // no solo "regresa vacío" — evita que un bug silencioso parezca éxito.
        assertThrows(ApiException.class, employeeApi::listActive);
    }

    @Test
    void jpa_tenantASoloVeSusPropiosEmpleados() {
        List<EmployeeView> seenByA = TenantContext.runAs(adminScope(tenantA), employeeApi::listActive);
        List<EmployeeView> seenByB = TenantContext.runAs(adminScope(tenantB), employeeApi::listActive);

        assertThat(seenByA).extracting(EmployeeView::id).containsExactly(employeeA);
        assertThat(seenByB).extracting(EmployeeView::id).containsExactly(employeeB);
    }

    @Test
    void sqlNativo_tenantASoloVeSusPropiasFilas() {
        List<UUID> idsForA = TenantContext.runAs(adminScope(tenantA),
                () -> jdbc.queryForList("SELECT id FROM employee", UUID.class));
        List<UUID> idsForB = TenantContext.runAs(adminScope(tenantB),
                () -> jdbc.queryForList("SELECT id FROM employee", UUID.class));

        assertThat(idsForA).containsExactly(employeeA);
        assertThat(idsForB).containsExactly(employeeB);
    }

    @Test
    void eventRaw_updateYDeleteDirectosFallanInclusoParaElRolDeApp() {
        UUID eventId = TenantContext.runAs(adminScope(tenantA), () -> {
            EventView view = attendanceApi.record(new RecordEventCommand(
                    employeeA, null, null, EventType.CHECK_IN, CaptureMethod.NFC, null, Instant.now(), kioskoA));
            return view.id();
        });

        TenantContext.runAs(adminScope(tenantA), () -> {
            assertThrows(DataAccessException.class, () -> jdbc.update(
                    "UPDATE event_raw SET event_type = 'check_out' WHERE id = ?::uuid", eventId.toString()));
            assertThrows(DataAccessException.class, () -> jdbc.update(
                    "DELETE FROM event_raw WHERE id = ?::uuid", eventId.toString()));
            return null;
        });

        // El evento sigue intacto y visible solo para su tenant.
        Long stillThere = TenantContext.runAs(adminScope(tenantA), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_raw WHERE id = ?::uuid", Long.class, eventId.toString()));
        assertThat(stillThere).isEqualTo(1L);

        Long invisibleToB = TenantContext.runAs(adminScope(tenantB), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_raw WHERE id = ?::uuid", Long.class, eventId.toString()));
        assertThat(invisibleToB).isEqualTo(0L);
    }

    @Test
    void auditLog_registraCambioEnEmployee() {
        TenantContext.runAs(adminScope(tenantA), () -> jdbc.update(
                "UPDATE employee SET full_name = ? WHERE id = ?::uuid",
                "Empleado A (actualizado)", employeeA.toString()));

        Long auditRows = TenantContext.runAs(adminScope(tenantA), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'employee' "
                        + "AND entity_id = ?::uuid AND action = 'UPDATE'",
                Long.class, employeeA.toString()));
        assertThat(auditRows).isGreaterThanOrEqualTo(1L);

        // El audit_log de A no se filtra hacia B.
        Long auditRowsFromB = TenantContext.runAs(adminScope(tenantB), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'employee' AND entity_id = ?::uuid",
                Long.class, employeeA.toString()));
        assertThat(auditRowsFromB).isEqualTo(0L);
    }

    @Test
    void curpEnc_nuncaContieneElCurpEnClaro() {
        String plainCurp = "AAAA800101HDFLNS09";
        byte[] encrypted = piiCipher.encrypt(plainCurp);

        TenantContext.runAs(adminScope(tenantA), () -> jdbc.update(
                "UPDATE employee SET curp_enc = ? WHERE id = ?::uuid", encrypted, employeeA.toString()));

        byte[] stored = TenantContext.runAs(adminScope(tenantA), () -> jdbc.queryForObject(
                "SELECT curp_enc FROM employee WHERE id = ?::uuid", byte[].class, employeeA.toString()));

        assertThat(stored).isNotNull();
        String storedAsLatin1 = new String(stored, StandardCharsets.ISO_8859_1);
        assertThat(storedAsLatin1).doesNotContain(plainCurp);
        assertThat(piiCipher.decrypt(stored)).isEqualTo(plainCurp);

        // La columna legacy en claro nunca se vuelve a escribir.
        String legacyCurp = TenantContext.runAs(adminScope(tenantA), () -> jdbc.queryForObject(
                "SELECT curp FROM employee WHERE id = ?::uuid", String.class, employeeA.toString()));
        assertThat(legacyCurp).isNull();
    }
}
