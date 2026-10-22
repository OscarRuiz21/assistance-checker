package com.assistance.attendance.api;

import com.assistance.attendance.api.CaptureMethod;
import com.assistance.attendance.api.EventType;

import java.time.Instant;
import java.util.UUID;

public record EventView(
        UUID id,
        UUID employeeId,
        String employeeName,
        String employeeNumber,
        EventType eventType,
        CaptureMethod captureMethod,
        Instant eventTime,
        Instant receivedAt,
        boolean valid
) {}
