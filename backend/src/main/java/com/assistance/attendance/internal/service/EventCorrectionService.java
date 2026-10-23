package com.assistance.attendance.internal.service;

import com.assistance.attendance.internal.domain.EventCorrection;
import com.assistance.attendance.internal.domain.EventRaw;
import com.assistance.attendance.internal.repo.EventCorrectionRepository;
import com.assistance.attendance.internal.repo.EventRawRepository;
import com.assistance.attendance.internal.service.exception.EventNotFoundException;
import com.assistance.shared.api.TenantContext;
import com.assistance.shared.api.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Servicio mínimo de correcciones sobre {@code event_raw}, siguiendo
 * ADR-002 (append-only + correcciones como nuevos registros). Sin endpoint
 * todavía — AGENTE A lo expondrá en un controller cuando el modelo de roles
 * (identity) esté listo para decidir quién puede autorizar correcciones.
 *
 * <p>La invalidación del evento original pasa por la función SQL
 * {@code invalidate_event_raw(...)} (SECURITY DEFINER, ver
 * V4__event_raw_append_only.sql) en vez de un UPDATE directo vía JPA: el rol
 * de aplicación ({@code app_db_user}) no tiene GRANT de UPDATE sobre
 * {@code event_raw}; solo puede invalidar a través de esa función, que
 * restringe los cambios a las columnas de invalidación.
 */
@Service
public class EventCorrectionService {

    private final EventRawRepository eventRawRepo;
    private final EventCorrectionRepository correctionRepo;
    private final JdbcTemplate jdbc;

    public EventCorrectionService(EventRawRepository eventRawRepo,
                                  EventCorrectionRepository correctionRepo,
                                  JdbcTemplate jdbc) {
        this.eventRawRepo = eventRawRepo;
        this.correctionRepo = correctionRepo;
        this.jdbc = jdbc;
    }

    /** Marca el evento original como inválido sin crear uno nuevo. */
    @Transactional
    public UUID invalidate(UUID eventRawId, String reason) {
        TenantScope scope = TenantContext.require();
        UUID actorUserId = requireActorUserId(scope);
        EventRaw original = eventRawRepo.findById(eventRawId)
                .filter(e -> e.getTenantId().equals(scope.tenantId()))
                .orElseThrow(EventNotFoundException::new);

        invalidateViaFunction(original.getId(), reason, actorUserId);
        return recordCorrection(scope.tenantId(), original.getId(), "invalidate", null, null, reason, actorUserId, null);
    }

    /**
     * Invalida el original (si se pasa) y crea un nuevo evento "manual" con
     * los datos corregidos. Si {@code eventRawIdToInvalidate} es null, es una
     * corrección tipo "create" (alta manual sin original).
     */
    @Transactional
    public UUID adjust(UUID eventRawIdToInvalidate,
                       UUID employeeId,
                       UUID plantId,
                       UUID kioskoId,
                       String newEventType,
                       String captureMethod,
                       Instant newEventTime,
                       String reason) {
        TenantScope scope = TenantContext.require();
        UUID actorUserId = requireActorUserId(scope);

        if (eventRawIdToInvalidate != null) {
            EventRaw original = eventRawRepo.findById(eventRawIdToInvalidate)
                    .filter(e -> e.getTenantId().equals(scope.tenantId()))
                    .orElseThrow(EventNotFoundException::new);
            invalidateViaFunction(original.getId(), reason, actorUserId);
        }

        EventRaw created = new EventRaw();
        created.setId(UUID.randomUUID());
        created.setTenantId(scope.tenantId());
        created.setPlantId(plantId);
        created.setKioskoId(kioskoId);
        created.setEmployeeId(employeeId);
        created.setEventType(newEventType);
        created.setCaptureMethod(captureMethod);
        created.setDeviceTime(newEventTime);
        created.setEventTime(newEventTime);
        created.setReceivedAt(Instant.now());
        created.setValid(true);
        created.setRecordedByUserId(actorUserId);
        EventRaw saved = eventRawRepo.save(created);

        String action = eventRawIdToInvalidate != null ? "adjust_time" : "create";
        recordCorrection(scope.tenantId(), eventRawIdToInvalidate, action,
                newEventTime, newEventType, reason, actorUserId, saved.getId());
        return saved.getId();
    }

    private void invalidateViaFunction(UUID eventRawId, String reason, UUID actorUserId) {
        jdbc.update("SELECT invalidate_event_raw(?::uuid, ?, ?::uuid)",
                eventRawId.toString(), reason, actorUserId.toString());
    }

    private UUID recordCorrection(UUID tenantId, UUID eventRawId, String action,
                                  Instant newEventTime, String newEventType,
                                  String reason, UUID authorizedBy, UUID resultingEventId) {
        EventCorrection correction = new EventCorrection();
        correction.setId(UUID.randomUUID());
        correction.setTenantId(tenantId);
        correction.setEventRawId(eventRawId);
        correction.setAction(action);
        correction.setNewEventTime(newEventTime);
        correction.setNewEventType(newEventType);
        correction.setReason(reason);
        correction.setAuthorizedBy(authorizedBy);
        correction.setAuthorizedAt(Instant.now());
        correction.setResultingEventId(resultingEventId);
        return correctionRepo.save(correction).getId();
    }

    private UUID requireActorUserId(TenantScope scope) {
        if (scope.userId() == null) {
            throw new IllegalStateException(
                    "Las correcciones requieren un usuario admin autenticado (no un kiosko ni un job de sistema)");
        }
        return scope.userId();
    }
}
