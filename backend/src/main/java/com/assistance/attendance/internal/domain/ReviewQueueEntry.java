package com.assistance.attendance.internal.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Cola de revisión para anomalías detectadas automáticamente (conflictos
 * multi-kiosko, check-in/check-out faltante, turno extendido, etc.) según
 * doc 03-modelo-datos.md. Mutable: a diferencia de {@code event_raw} y
 * {@code event_correction}, aquí sí se permite UPDATE para transicionar
 * {@code status} y registrar la resolución.
 */
@Entity
@Table(name = "review_queue")
public class ReviewQueueEntry {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(name = "employee_id")
    private UUID employeeId;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(nullable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "related_event_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] relatedEventIds = new UUID[0];

    @Column(nullable = false)
    private String status = "pending";

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    private String resolution;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getPlantId() { return plantId; }
    public void setPlantId(UUID plantId) { this.plantId = plantId; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public UUID[] getRelatedEventIds() { return relatedEventIds; }
    public void setRelatedEventIds(UUID[] relatedEventIds) { this.relatedEventIds = relatedEventIds; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(UUID resolvedBy) { this.resolvedBy = resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
}
