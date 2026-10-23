package com.assistance.identity.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kiosko_device_token")
public class KioskoDeviceToken {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "kiosko_id", nullable = false)
    private UUID kioskoId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected KioskoDeviceToken() {
    }

    public KioskoDeviceToken(UUID id, UUID tenantId, UUID kioskoId, String tokenHash, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.kioskoId = kioskoId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getKioskoId() { return kioskoId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public boolean isActive() {
        return revokedAt == null;
    }
}
