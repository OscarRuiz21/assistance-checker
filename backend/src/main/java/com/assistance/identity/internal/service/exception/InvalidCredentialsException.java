package com.assistance.identity.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidCredentialsException extends ApiException {
    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email o contraseña incorrectos");
    }
}
