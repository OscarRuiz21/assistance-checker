package com.assistance.employee.api;

import com.assistance.employee.api.EmployeeStatus;

import java.util.UUID;

public record EmployeeView(
        UUID id,
        UUID tenantId,
        String employeeNumber,
        String fullName,
        UUID defaultPlantId,
        EmployeeStatus status
) {}
