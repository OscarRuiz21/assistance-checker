package com.assistance.attendance.internal.web;

import com.assistance.attendance.api.AttendanceApi;
import com.assistance.attendance.api.DailyBreakdown;
import com.assistance.attendance.api.EmployeeWorkSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Endpoints de agregaciones por empleado. Vive en attendance porque las
 * queries van contra event_raw, aunque expone datos enriquecidos con info
 * del empleado (vía EmployeeApi adentro del service).
 */
@RestController
@RequestMapping("/api/employees")
public class EmployeeStatsController {

    private static final ZoneId TZ_MX = ZoneId.of("America/Mexico_City");

    private final AttendanceApi attendance;

    public EmployeeStatsController(AttendanceApi attendance) {
        this.attendance = attendance;
    }

    @GetMapping("/summaries")
    public List<EmployeeWorkSummary> summaries(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        var range = resolveRange(from, to, 14);
        return attendance.summarize(range.from(), range.to());
    }

    @GetMapping("/{id}/daily")
    public List<DailyBreakdown> daily(
            @PathVariable UUID id,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        var range = resolveRange(from, to, 30);
        return attendance.dailyBreakdown(id, range.from(), range.to());
    }

    private record Range(Instant from, Instant to) {}

    private Range resolveRange(LocalDate from, LocalDate to, int defaultDaysBack) {
        LocalDate fromD = from != null ? from : LocalDate.now(TZ_MX).minusDays(defaultDaysBack);
        LocalDate toD = to != null ? to : LocalDate.now(TZ_MX);
        return new Range(
                fromD.atStartOfDay(TZ_MX).toInstant(),
                toD.plusDays(1).atStartOfDay(TZ_MX).toInstant());
    }
}
