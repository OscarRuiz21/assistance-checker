package com.assistance.attendance.internal.web;

import com.assistance.attendance.api.AttendanceApi;
import com.assistance.attendance.api.DailyBreakdown;
import com.assistance.attendance.api.EmployeeWorkSummary;
import com.assistance.attendance.internal.service.TimezoneResolver;
import com.assistance.shared.api.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
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

    private final AttendanceApi attendance;
    private final TimezoneResolver timezoneResolver;

    public EmployeeStatsController(AttendanceApi attendance, TimezoneResolver timezoneResolver) {
        this.attendance = attendance;
        this.timezoneResolver = timezoneResolver;
    }

    @GetMapping("/summaries")
    @PreAuthorize("hasAnyRole('super_admin','tenant_admin','rh','supervisor')")
    public List<EmployeeWorkSummary> summaries(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        var range = resolveRange(from, to, 14);
        return attendance.summarize(range.from(), range.to());
    }

    @GetMapping("/{id}/daily")
    @PreAuthorize("hasAnyRole('super_admin','tenant_admin','rh','supervisor')")
    public List<DailyBreakdown> daily(
            @PathVariable UUID id,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        var range = resolveRange(from, to, 30);
        return attendance.dailyBreakdown(id, range.from(), range.to());
    }

    private record Range(Instant from, Instant to) {}

    private Range resolveRange(LocalDate from, LocalDate to, int defaultDaysBack) {
        ZoneId tz = ZoneId.of(timezoneResolver.resolve(TenantContext.require()));
        LocalDate fromD = from != null ? from : LocalDate.now(tz).minusDays(defaultDaysBack);
        LocalDate toD = to != null ? to : LocalDate.now(tz);
        return new Range(
                fromD.atStartOfDay(tz).toInstant(),
                toD.plusDays(1).atStartOfDay(tz).toInstant());
    }
}
