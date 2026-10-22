package com.assistance.attendance.internal.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_raw")
public class EventRaw {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(name = "kiosko_id", nullable = false)
    private UUID kioskoId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "capture_method", nullable = false)
    private String captureMethod;

    @Column(name = "device_time", nullable = false)
    private Instant deviceTime;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "nfc_card_id")
    private UUID nfcCardId;

    @Column(name = "is_valid", nullable = false)
    private boolean valid = true;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getPlantId() { return plantId; }
    public void setPlantId(UUID plantId) { this.plantId = plantId; }
    public UUID getKioskoId() { return kioskoId; }
    public void setKioskoId(UUID kioskoId) { this.kioskoId = kioskoId; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getCaptureMethod() { return captureMethod; }
    public void setCaptureMethod(String captureMethod) { this.captureMethod = captureMethod; }
    public Instant getDeviceTime() { return deviceTime; }
    public void setDeviceTime(Instant deviceTime) { this.deviceTime = deviceTime; }
    public Instant getEventTime() { return eventTime; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public UUID getNfcCardId() { return nfcCardId; }
    public void setNfcCardId(UUID nfcCardId) { this.nfcCardId = nfcCardId; }
    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
}
