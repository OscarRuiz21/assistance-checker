package com.assistance.employee.internal.service.mapper;

import com.assistance.employee.internal.domain.Employee;
import com.assistance.employee.api.EmployeeStatus;
import com.assistance.employee.api.EmployeeView;
import org.springframework.stereotype.Component;

@Component
public class EmployeeMapper {

    public EmployeeView toView(Employee e) {
        return new EmployeeView(
                e.getId(),
                e.getTenantId(),
                e.getEmployeeNumber(),
                e.getFullName(),
                e.getDefaultPlantId(),
                EmployeeStatus.fromDb(e.getStatus()));
    }
}
