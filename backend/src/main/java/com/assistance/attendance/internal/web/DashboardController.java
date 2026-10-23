package com.assistance.attendance.internal.web;

import com.assistance.attendance.api.AttendanceApi;
import com.assistance.attendance.api.DashboardKpis;
import com.assistance.attendance.internal.service.TimezoneResolver;
import com.assistance.shared.api.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final AttendanceApi attendance;
    private final TimezoneResolver timezoneResolver;

    public DashboardController(AttendanceApi attendance, TimezoneResolver timezoneResolver) {
        this.attendance = attendance;
        this.timezoneResolver = timezoneResolver;
    }

    @GetMapping("/kpis")
    @PreAuthorize("hasAnyRole('super_admin','tenant_admin','rh','supervisor')")
    public DashboardKpis kpis() {
        ZoneId tz = ZoneId.of(timezoneResolver.resolve(TenantContext.require()));
        LocalDate today = LocalDate.now(tz);
        Instant from = today.atStartOfDay(tz).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(tz).toInstant();
        return attendance.dashboard(from, to);
    }
}
