package com.assistance.attendance.api;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Desglose de un día de un empleado: primer check-in, primera salida a comer,
 * primer regreso de comer, última salida del día, y los segundos calculados.
 * Cualquier evento puede ser null si no se registró.
 */
public record DailyBreakdown(
        LocalDate businessDate,
        Instant checkIn,
        Instant lunchOut,
        Instant lunchIn,
        Instant checkOut,
        long secondsInOffice,   // checkOut - checkIn (0 si falta uno)
        long secondsLunch,      // lunchIn - lunchOut (0 si falta uno)
        long secondsWorked,     // inOffice - lunch (0 si no hay datos)
        boolean isComplete      // true si tiene checkIn AND checkOut
) {}
