package com.assistance.attendance.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class PinRequiredException extends ApiException {
    public PinRequiredException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "PIN_REQUIRED",
                "PIN requerido para este método de captura");
    }
}
