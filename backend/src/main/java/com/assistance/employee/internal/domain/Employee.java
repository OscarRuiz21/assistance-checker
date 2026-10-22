package com.assistance.employee.internal.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "employee")
public class Employee {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "employee_number", nullable = false)
    private String employeeNumber;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "default_plant_id")
    private UUID defaultPlantId;

    @Column(name = "pin_hash")
    private String pinHash;

    @Column(nullable = false)
    private String status;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getEmployeeNumber() { return employeeNumber; }
    public String getFullName() { return fullName; }
    public UUID getDefaultPlantId() { return defaultPlantId; }
    public String getPinHash() { return pinHash; }
    public String getStatus() { return status; }
    public LocalDate getHireDate() { return hireDate; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
