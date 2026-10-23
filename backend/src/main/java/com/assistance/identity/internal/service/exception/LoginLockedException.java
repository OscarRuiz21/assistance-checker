package com.assistance.identity.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class LoginLockedException extends ApiException {
    public LoginLockedException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_LOCKED",
                "Demasiados intentos fallidos. Intente de nuevo en unos minutos");
    }
}
