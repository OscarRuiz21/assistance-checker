package com.assistance.attendance.internal.web;

import com.assistance.attendance.api.AttendanceApi;
import com.assistance.attendance.api.DashboardKpis;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final ZoneId TZ_MX = ZoneId.of("America/Mexico_City");

    private final AttendanceApi attendance;

    public DashboardController(AttendanceApi attendance) {
        this.attendance = attendance;
    }

    @GetMapping("/kpis")
    public DashboardKpis kpis() {
        LocalDate today = LocalDate.now(TZ_MX);
        Instant from = today.atStartOfDay(TZ_MX).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(TZ_MX).toInstant();
        return attendance.dashboard(from, to);
    }
}
