package com.assistance.attendance.api;

import com.assistance.attendance.api.CaptureMethod;
import com.assistance.attendance.api.EventType;

import java.time.Instant;
import java.util.UUID;

public record RecordEventCommand(
        UUID employeeId,
        String cardUid,
        String employeeNumber,
        EventType eventType,
        CaptureMethod captureMethod,
        String pin,
        Instant deviceTime,
        UUID kioskoId
) {}
