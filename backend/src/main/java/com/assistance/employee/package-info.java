/**
 * Módulo Employee.
 *
 * NamedInterfaces públicos:
 *   employee::api  — port (EmployeeApi) + view (EmployeeView) + value (EmployeeStatus)
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Employee",
        allowedDependencies = {"shared::api", "device::api"}
)
package com.assistance.employee;
