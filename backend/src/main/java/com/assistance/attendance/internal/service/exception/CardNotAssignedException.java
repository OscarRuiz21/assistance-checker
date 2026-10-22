package com.assistance.attendance.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class CardNotAssignedException extends ApiException {
    public CardNotAssignedException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "CARD_NOT_ASSIGNED",
                "Tarjeta sin empleado asignado");
    }
}
