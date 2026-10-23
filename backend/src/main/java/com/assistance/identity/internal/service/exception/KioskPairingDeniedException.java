package com.assistance.identity.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class KioskPairingDeniedException extends ApiException {
    public KioskPairingDeniedException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "KIOSK_PAIRING_DENIED", message);
    }
}
