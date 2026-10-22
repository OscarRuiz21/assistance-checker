package com.assistance.employee.api;

import com.assistance.employee.api.EmployeeView;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeApi {

    Optional<EmployeeView> findActive(UUID employeeId);

    Optional<EmployeeView> findActiveByEmployeeNumber(String employeeNumber);

    List<EmployeeView> listActive();

    boolean verifyPin(UUID employeeId, String pin);
}
