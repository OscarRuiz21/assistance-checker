package com.assistance.device.internal.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "nfc_card")
public class NfcCard {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String uid;

    @Column(name = "employee_id")
    private UUID employeeId;

    @Column(nullable = false)
    private String status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "blocked_at")
    private Instant blockedAt;

    @Column(name = "blocked_reason")
    private String blockedReason;

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getUid() { return uid; }
    public UUID getEmployeeId() { return employeeId; }
    public String getStatus() { return status; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getBlockedAt() { return blockedAt; }
    public String getBlockedReason() { return blockedReason; }
}
