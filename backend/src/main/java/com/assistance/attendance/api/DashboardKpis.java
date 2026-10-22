package com.assistance.attendance.api;

import java.time.Instant;

public record DashboardKpis(
        long totalEntries,
        long uniqueEmployees,
        long lateCount,
        Instant from,
        Instant to
) {}
