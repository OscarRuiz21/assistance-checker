package com.assistance.attendance.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Agregación por empleado en un rango de tiempo. "secondsInOffice" es el
 * tiempo total entre primer check-in y último check-out de cada día.
 * "secondsLunch" es el tiempo total de comida agregado. "secondsWorked" es
 * la diferencia (lo que efectivamente trabajó).
 */
public record EmployeeWorkSummary(
        UUID employeeId,
        String employeeName,
        String employeeNumber,
        int daysWorked,
        int totalEvents,
        long secondsInOffice,
        long secondsLunch,
        long secondsWorked,
        Instant lastCheckIn
) {}
