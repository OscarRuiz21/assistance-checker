package com.assistance.attendance.internal.web;

import com.assistance.attendance.api.CaptureMethod;
import com.assistance.attendance.api.EventType;
import com.assistance.attendance.api.EventView;
import com.assistance.attendance.event.AttendanceRecorded;
import com.assistance.employee.api.EmployeeApi;
import com.assistance.employee.api.EmployeeView;
import com.assistance.shared.api.TenantContext;
import com.assistance.shared.api.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hub que mantiene la lista de suscriptores SSE activos y broadcasta cada
 * AttendanceRecorded enriquecido con datos del empleado SOLO a los clientes
 * del mismo tenant (y, si el suscriptor está acotado a ciertas plantas, solo
 * si el evento pertenece a una de ellas). Requisito Fase 2.5.
 * También envía heartbeats cada 25s para evitar que proxies cierren la conexión.
 */
@Component
public class EventStreamHub {

    private static final Logger log = LoggerFactory.getLogger(EventStreamHub.class);

    /** Tenant + plantas visibles de cada suscriptor, capturados al conectarse. */
    private record Subscriber(SseEmitter emitter, UUID tenantId, Set<UUID> plantIds) {
        boolean canSee(UUID eventTenantId, UUID eventPlantId) {
            return tenantId.equals(eventTenantId)
                    && (plantIds.isEmpty() || plantIds.contains(eventPlantId));
        }
    }

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final EmployeeApi employees;

    public EventStreamHub(EmployeeApi employees) {
        this.employees = employees;
    }

    /**
     * Registra al suscriptor actual usando el {@link TenantContext} del hilo
     * de la request HTTP (poblado por el filtro de seguridad antes de que el
     * controller llegue aquí). Sin tenant en contexto, se rechaza (401) en
     * vez de registrar un emitter que nunca recibiría nada de forma
     * silenciosa.
     */
    public SseEmitter register() {
        TenantScope scope = TenantContext.require();
        SseEmitter emitter = new SseEmitter(0L); // sin timeout server-side
        Subscriber subscriber = new Subscriber(emitter, scope.tenantId(), scope.plantIds());
        emitter.onCompletion(() -> subscribers.removeIf(s -> s.emitter() == emitter));
        emitter.onTimeout(() -> { emitter.complete(); subscribers.removeIf(s -> s.emitter() == emitter); });
        emitter.onError(t -> subscribers.removeIf(s -> s.emitter() == emitter));
        subscribers.add(subscriber);
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            subscribers.removeIf(s -> s.emitter() == emitter);
        }
        return emitter;
    }

    @ApplicationModuleListener
    public void on(AttendanceRecorded event) {
        EmployeeView employee = employees.findActive(event.employeeId()).orElse(null);
        EventView view = new EventView(
                event.eventId(),
                event.employeeId(),
                employee != null ? employee.fullName() : "?",
                employee != null ? employee.employeeNumber() : "?",
                event.eventType(),
                event.captureMethod(),
                event.eventTime(),
                Instant.now(),
                true);
        broadcast("attendance", view, event.tenantId(), event.plantId());
    }

    @Scheduled(fixedRate = 25_000L)
    public void heartbeat() {
        if (subscribers.isEmpty()) return;
        for (Subscriber s : subscribers) {
            try {
                s.emitter().send(SseEmitter.event().comment("ping"));
            } catch (IOException e) {
                subscribers.remove(s);
            }
        }
    }

    private void broadcast(String name, Object payload, UUID eventTenantId, UUID eventPlantId) {
        if (subscribers.isEmpty()) return;
        for (Subscriber s : subscribers) {
            if (!s.canSee(eventTenantId, eventPlantId)) continue;
            try {
                s.emitter().send(SseEmitter.event().name(name).data(payload));
            } catch (IOException e) {
                log.debug("emitter caído, removiendo", e);
                subscribers.remove(s);
            } catch (IllegalStateException e) {
                subscribers.remove(s);
            }
        }
    }

    int activeConnections() {
        return subscribers.size();
    }

    /** Helper para diagnóstico de captureMethod/eventType en tests. */
    @SuppressWarnings("unused")
    static EventType castEventType(String s) { return EventType.fromDb(s); }
    @SuppressWarnings("unused")
    static CaptureMethod castCaptureMethod(String s) { return CaptureMethod.fromDb(s); }
}
