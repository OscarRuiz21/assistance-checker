package com.assistance.attendance.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class EmployeeNotFoundException extends ApiException {
    public EmployeeNotFoundException(String hint) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "EMPLOYEE_NOT_FOUND",
                hint == null ? "Empleado no encontrado" : "Empleado no encontrado: " + hint);
    }
}
