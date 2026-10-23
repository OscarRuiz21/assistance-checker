package com.assistance.attendance.internal.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de corrección sobre un {@link EventRaw}, según ADR-002: el evento
 * original nunca se modifica más allá de los flags de invalidación; cada
 * corrección queda trazada aquí con quién la autorizó y por qué.
 */
@Entity
@Table(name = "event_correction")
public class EventCorrection {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_raw_id")
    private UUID eventRawId;

    @Column(nullable = false)
    private String action;

    @Column(name = "new_event_time")
    private Instant newEventTime;

    @Column(name = "new_event_type")
    private String newEventType;

    @Column(nullable = false)
    private String reason;

    @Column(name = "authorized_by", nullable = false)
    private UUID authorizedBy;

    @Column(name = "authorized_at", nullable = false)
    private Instant authorizedAt;

    @Column(name = "resulting_event_id")
    private UUID resultingEventId;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getEventRawId() { return eventRawId; }
    public void setEventRawId(UUID eventRawId) { this.eventRawId = eventRawId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Instant getNewEventTime() { return newEventTime; }
    public void setNewEventTime(Instant newEventTime) { this.newEventTime = newEventTime; }
    public String getNewEventType() { return newEventType; }
    public void setNewEventType(String newEventType) { this.newEventType = newEventType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public UUID getAuthorizedBy() { return authorizedBy; }
    public void setAuthorizedBy(UUID authorizedBy) { this.authorizedBy = authorizedBy; }
    public Instant getAuthorizedAt() { return authorizedAt; }
    public void setAuthorizedAt(Instant authorizedAt) { this.authorizedAt = authorizedAt; }
    public UUID getResultingEventId() { return resultingEventId; }
    public void setResultingEventId(UUID resultingEventId) { this.resultingEventId = resultingEventId; }
}
