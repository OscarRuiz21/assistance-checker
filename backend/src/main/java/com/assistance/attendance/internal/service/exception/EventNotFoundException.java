package com.assistance.attendance.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class EventNotFoundException extends ApiException {
    public EventNotFoundException() {
        super(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "Evento no encontrado");
    }
}
