package com.assistance.attendance.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class CardBlockedException extends ApiException {
    public CardBlockedException(String reason) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "NFC_BLOCKED",
                "Tarjeta bloqueada: " + (reason == null || reason.isBlank() ? "sin motivo" : reason));
    }
}
