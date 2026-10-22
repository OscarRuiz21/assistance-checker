package com.assistance.attendance.internal.web.dto;

import com.assistance.attendance.api.RecordEventCommand;
import com.assistance.attendance.api.CaptureMethod;
import com.assistance.attendance.api.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record CreateEventDto(
        UUID employeeId,
        String cardUid,
        String employeeNumber,
        @NotBlank String eventType,
        @NotBlank String captureMethod,
        String pin,
        @NotNull Instant deviceTime,
        UUID kioskoId
) {
    public RecordEventCommand toCommand() {
        return new RecordEventCommand(
                employeeId, cardUid, employeeNumber,
                EventType.fromDb(eventType),
                CaptureMethod.fromDb(captureMethod),
                pin, deviceTime, kioskoId);
    }
}
