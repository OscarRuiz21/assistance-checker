package com.assistance.attendance.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class MissingIdentifierException extends ApiException {
    public MissingIdentifierException() {
        super(HttpStatus.BAD_REQUEST, "MISSING_IDENTIFIER",
                "Debe enviar employeeId, cardUid o employeeNumber");
    }
}
