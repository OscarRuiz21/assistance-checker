package com.assistance.employee.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class CardNotFoundException extends ApiException {
    public CardNotFoundException() {
        super(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND",
                "Tarjeta no encontrada o no asignada a empleado activo");
    }
}
