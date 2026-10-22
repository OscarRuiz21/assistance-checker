package com.assistance.employee.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class EmployeeNotFoundException extends ApiException {
    public EmployeeNotFoundException() {
        super(HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND", "Empleado no encontrado");
    }
}
