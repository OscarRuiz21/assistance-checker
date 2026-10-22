package com.assistance.attendance.internal.web;

import com.assistance.attendance.api.CaptureMethod;
import com.assistance.attendance.api.EventType;
import com.assistance.attendance.api.EventView;
import com.assistance.attendance.event.AttendanceRecorded;
import com.assistance.employee.api.EmployeeApi;
import com.assistance.employee.api.EmployeeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hub que mantiene la lista de SseEmitter activos y broadcasta cada
 * AttendanceRecorded enriquecido con datos del empleado a todos los clientes.
 * También envía heartbeats cada 25s para evitar que proxies cierren la conexión.
 */
@Component
public class EventStreamHub {

    private static final Logger log = LoggerFactory.getLogger(EventStreamHub.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final EmployeeApi employees;

    public EventStreamHub(EmployeeApi employees) {
        this.employees = employees;
    }

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L); // sin timeout server-side
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> { emitter.complete(); emitters.remove(emitter); });
        emitter.onError(t -> emitters.remove(emitter));
        emitters.add(emitter);
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            emitters.remove(emitter);
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
        broadcast("attendance", view);
    }

    @Scheduled(fixedRate = 25_000L)
    public void heartbeat() {
        if (emitters.isEmpty()) return;
        for (SseEmitter em : emitters) {
            try {
                em.send(SseEmitter.event().comment("ping"));
            } catch (IOException e) {
                emitters.remove(em);
            }
        }
    }

    private void broadcast(String name, Object payload) {
        if (emitters.isEmpty()) return;
        for (SseEmitter em : emitters) {
            try {
                em.send(SseEmitter.event().name(name).data(payload));
            } catch (IOException e) {
                log.debug("emitter caído, removiendo", e);
                emitters.remove(em);
            } catch (IllegalStateException e) {
                emitters.remove(em);
            }
        }
    }

    int activeConnections() {
        return emitters.size();
    }

    /** Helper para diagnóstico de captureMethod/eventType en tests. */
    @SuppressWarnings("unused")
    static EventType castEventType(String s) { return EventType.fromDb(s); }
    @SuppressWarnings("unused")
    static CaptureMethod castCaptureMethod(String s) { return CaptureMethod.fromDb(s); }
}
