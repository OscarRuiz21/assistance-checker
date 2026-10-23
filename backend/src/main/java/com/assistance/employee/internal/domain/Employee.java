package com.assistance.employee.internal.domain;

import com.assistance.employee.internal.crypto.PiiAttributeConverter;
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

    /**
     * CURP/RFC/NSS cifrados en columna (AES-256-GCM, ver PiiCipher/V8-V9).
     * Las columnas legacy en claro ({@code curp}, {@code rfc}, {@code nss})
     * siguen existiendo en el schema (seed de demo las usa) pero
     * deliberadamente NO se mapean en esta entidad: nada en el código Java
     * debe volver a leer/escribir PII en claro después de V9.
     */
    @Convert(converter = PiiAttributeConverter.class)
    @Column(name = "curp_enc")
    private String curp;

    @Convert(converter = PiiAttributeConverter.class)
    @Column(name = "rfc_enc")
    private String rfc;

    @Convert(converter = PiiAttributeConverter.class)
    @Column(name = "nss_enc")
    private String nss;

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
    public String getCurp() { return curp; }
    public void setCurp(String curp) { this.curp = curp; }
    public String getRfc() { return rfc; }
    public void setRfc(String rfc) { this.rfc = rfc; }
    public String getNss() { return nss; }
    public void setNss(String nss) { this.nss = nss; }
    public String getStatus() { return status; }
    public LocalDate getHireDate() { return hireDate; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
