package com.assistance.demo.internal.service;

import com.assistance.attendance.api.AttendanceApi;
import com.assistance.attendance.api.CaptureMethod;
import com.assistance.attendance.api.EventType;
import com.assistance.attendance.api.RecordEventCommand;
import com.assistance.shared.api.TenantContext;
import com.assistance.shared.api.TenantScope;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Service que orquesta la generación de datos de demo. Usa AttendanceApi
 * (no JdbcTemplate directo) para que cada evento siga el flujo real:
 * resolver empleado, validar y publicar AttendanceRecorded.
 *
 * Genera datos de oficinistas: 5 empleados, 14 días hábiles (lun-vie),
 * llegada 8:30-9:00, comida 13:00-14:00, salida 17:30-18:30. 95% asistencia.
 *
 * <p>Solo activo en los perfiles {@code local} y {@code demo} (Fase 2.6):
 * fuera de esos perfiles ni este bean ni {@code DemoController} (que
 * depende de él) existen, así que el endpoint {@code /api/demo/*} no puede
 * invocarse en {@code prod}/{@code staging} aunque alguien lo intentara.
 *
 * <p>Todo el seed corre con {@code TenantContext.runAs(TenantScope.system(...))}
 * porque es un job administrativo, no una request de usuario/kiosko: no hay
 * JWT ni token de kiosko del cual tomar el tenant.
 */
@Service
@Profile({"local", "demo"})
public class DemoSeedService {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final int OFFICE_EMPLOYEES = 5;
    private static final int WORKDAYS = 14;

    private final AttendanceApi attendance;
    private final JdbcTemplate jdbc;

    public DemoSeedService(AttendanceApi attendance, JdbcTemplate jdbc) {
        this.attendance = attendance;
        this.jdbc = jdbc;
    }

    public int seed() {
        return TenantContext.runAs(TenantScope.system(TENANT), this::seedInternal);
    }

    public int reset() {
        return TenantContext.runAs(TenantScope.system(TENANT), this::resetInternal);
    }

    private int seedInternal() {
        ZoneId tz = resolveDemoTimezone();
        List<UUID> employeeIds = jdbc.queryForList(
                "SELECT id FROM employee WHERE tenant_id = ?::uuid AND status = 'active' "
                        + "ORDER BY employee_number LIMIT " + OFFICE_EMPLOYEES,
                String.class, TENANT.toString())
                .stream().map(UUID::fromString).toList();

        List<LocalDate> workdays = lastWeekdays(WORKDAYS, tz);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        int generated = 0;
        for (LocalDate day : workdays) {
            for (UUID empId : employeeIds) {
                if (rnd.nextDouble() > 0.95) continue; // 95% asistencia

                // Llegada: 8:30-9:00
                Instant ci = day.atTime(8, 30 + rnd.nextInt(0, 30), rnd.nextInt(0, 60))
                        .atZone(tz).toInstant();
                // Salida a comer: 13:00-13:30
                Instant lo = day.atTime(13, rnd.nextInt(0, 30), rnd.nextInt(0, 60))
                        .atZone(tz).toInstant();
                // Vuelve de comer: 14:00-14:15 (~1h después)
                Instant li = day.atTime(14, rnd.nextInt(0, 15), rnd.nextInt(0, 60))
                        .atZone(tz).toInstant();
                // Salida: 17:30-18:30
                int outH = rnd.nextInt(17, 19);
                int outM = (outH == 17) ? 30 + rnd.nextInt(0, 30) : rnd.nextInt(0, 30);
                Instant co = day.atTime(outH, outM, rnd.nextInt(0, 60))
                        .atZone(tz).toInstant();

                attendance.record(new RecordEventCommand(
                        empId, null, null, EventType.CHECK_IN, CaptureMethod.NFC, null, ci, null));
                attendance.record(new RecordEventCommand(
                        empId, null, null, EventType.LUNCH_OUT, CaptureMethod.NFC, null, lo, null));
                attendance.record(new RecordEventCommand(
                        empId, null, null, EventType.LUNCH_IN, CaptureMethod.NFC, null, li, null));
                attendance.record(new RecordEventCommand(
                        empId, null, null, EventType.CHECK_OUT, CaptureMethod.NFC, null, co, null));
                generated += 4;
            }
        }
        return generated;
    }

    /**
     * {@code event_raw} es append-only desde V4 (trigger que rechaza
     * DELETE); el rol de la app ni siquiera tiene GRANT DELETE sobre la
     * tabla. El reset de demo pasa por la función SQL
     * {@code demo_reset_events(tenant)} (SECURITY DEFINER, ver
     * V4__event_raw_append_only.sql), que habilita el bypass del trigger
     * SOLO dentro de su propia transacción/sesión antes de borrar, y
     * exclusivamente para el tenant de demo — no es un DELETE libre.
     */
    private int resetInternal() {
        Integer deleted = jdbc.queryForObject(
                "SELECT demo_reset_events(?::uuid)", Integer.class, TENANT.toString());
        return deleted == null ? 0 : deleted;
    }

    private ZoneId resolveDemoTimezone() {
        String tz = jdbc.queryForObject(
                "SELECT COALESCE(p.timezone, t.timezone) FROM tenant t "
                        + "LEFT JOIN plant p ON p.tenant_id = t.id "
                        + "WHERE t.id = ?::uuid LIMIT 1",
                String.class, TENANT.toString());
        return ZoneId.of(tz != null ? tz : "America/Mexico_City");
    }

    private List<LocalDate> lastWeekdays(int howMany, ZoneId tz) {
        List<LocalDate> result = new ArrayList<>();
        LocalDate cursor = LocalDate.now(tz).minusDays(1); // empezar ayer
        while (result.size() < howMany) {
            DayOfWeek dow = cursor.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                result.add(cursor);
            }
            cursor = cursor.minusDays(1);
        }
        return result;
    }
}
