package com.assistance.employee.internal.repo;

import com.assistance.employee.internal.domain.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    Optional<Employee> findByEmployeeNumberAndStatus(String employeeNumber, String status);

    List<Employee> findAllByStatusOrderByFullName(String status);
}
