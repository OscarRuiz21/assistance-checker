package com.assistance.attendance.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class CardLostException extends ApiException {
    public CardLostException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "NFC_LOST", "Tarjeta reportada perdida");
    }
}
