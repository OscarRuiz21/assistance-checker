package com.assistance.attendance.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidPinException extends ApiException {
    public InvalidPinException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PIN", "PIN incorrecto");
    }
}
