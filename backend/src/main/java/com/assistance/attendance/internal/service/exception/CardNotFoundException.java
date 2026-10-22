package com.assistance.attendance.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class CardNotFoundException extends ApiException {
    public CardNotFoundException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "CARD_NOT_FOUND", "Tarjeta no registrada");
    }
}
