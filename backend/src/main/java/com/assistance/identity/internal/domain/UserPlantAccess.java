package com.assistance.identity.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_plant_access")
public class UserPlantAccess {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserPlantAccess() {
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getUserId() { return userId; }
    public UUID getPlantId() { return plantId; }
    public Instant getCreatedAt() { return createdAt; }
}
