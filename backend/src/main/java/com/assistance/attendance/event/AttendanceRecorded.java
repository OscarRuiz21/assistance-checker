package com.assistance.attendance.event;

import com.assistance.attendance.api.CaptureMethod;
import com.assistance.attendance.api.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio publicado tras commit cuando se registra una asistencia.
 * Otros módulos escuchan con {@code @ApplicationModuleListener}.
 */
public record AttendanceRecorded(
        UUID eventId,
        UUID tenantId,
        UUID plantId,
        UUID employeeId,
        EventType eventType,
        CaptureMethod captureMethod,
        Instant eventTime
) {}
