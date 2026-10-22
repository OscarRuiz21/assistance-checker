package com.assistance.demo.internal.service;

import com.assistance.attendance.api.AttendanceApi;
import com.assistance.attendance.api.CaptureMethod;
import com.assistance.attendance.api.EventType;
import com.assistance.attendance.api.RecordEventCommand;
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
 */
@Service
public class DemoSeedService {

    private static final ZoneId TZ_MX = ZoneId.of("America/Mexico_City");
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
        List<UUID> employeeIds = jdbc.queryForList(
                "SELECT id FROM employee WHERE tenant_id = ?::uuid AND status = 'active' "
                        + "ORDER BY employee_number LIMIT " + OFFICE_EMPLOYEES,
                String.class, TENANT.toString())
                .stream().map(UUID::fromString).toList();

        List<LocalDate> workdays = lastWeekdays(WORKDAYS);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        int generated = 0;
        for (LocalDate day : workdays) {
            for (UUID empId : employeeIds) {
                if (rnd.nextDouble() > 0.95) continue; // 95% asistencia

                // Llegada: 8:30-9:00
                Instant ci = day.atTime(8, 30 + rnd.nextInt(0, 30), rnd.nextInt(0, 60))
                        .atZone(TZ_MX).toInstant();
                // Salida a comer: 13:00-13:30
                Instant lo = day.atTime(13, rnd.nextInt(0, 30), rnd.nextInt(0, 60))
                        .atZone(TZ_MX).toInstant();
                // Vuelve de comer: 14:00-14:15 (~1h después)
                Instant li = day.atTime(14, rnd.nextInt(0, 15), rnd.nextInt(0, 60))
                        .atZone(TZ_MX).toInstant();
                // Salida: 17:30-18:30
                int outH = rnd.nextInt(17, 19);
                int outM = (outH == 17) ? 30 + rnd.nextInt(0, 30) : rnd.nextInt(0, 30);
                Instant co = day.atTime(outH, outM, rnd.nextInt(0, 60))
                        .atZone(TZ_MX).toInstant();

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

    public int reset() {
        return jdbc.update("DELETE FROM event_raw WHERE tenant_id = ?::uuid", TENANT.toString());
    }

    private List<LocalDate> lastWeekdays(int howMany) {
        List<LocalDate> result = new ArrayList<>();
        LocalDate cursor = LocalDate.now(TZ_MX).minusDays(1); // empezar ayer
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
