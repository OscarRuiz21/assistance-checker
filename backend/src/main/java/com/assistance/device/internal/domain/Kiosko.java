package com.assistance.device.internal.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kiosko")
public class Kiosko {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "enabled_methods", nullable = false, columnDefinition = "text[]")
    private String[] enabledMethods;

    @Column(nullable = false)
    private String status;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getPlantId() { return plantId; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String[] getEnabledMethods() { return enabledMethods; }
    public String getStatus() { return status; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
}
