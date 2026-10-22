package com.assistance.attendance.internal.service.exception;

import com.assistance.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class EmployeeInactiveException extends ApiException {
    public EmployeeInactiveException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "EMPLOYEE_INACTIVE", "Empleado dado de baja");
    }
}
