package com.assistance.demo.internal.service;

import com.assistance.attendance.event.AttendanceRecorded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Listener async cross-módulo. Demuestra el patrón que se usará para SSE,
 * notificaciones a supervisor y triggers del motor de cálculo de horas.
 *
 * {@code @ApplicationModuleListener} se ejecuta tras commit de la transacción
 * que publicó el evento, en una transacción nueva, opcionalmente async.
 */
@Component
public class AttendanceLogListener {

    private static final Logger log = LoggerFactory.getLogger(AttendanceLogListener.class);

    @ApplicationModuleListener
    public void on(AttendanceRecorded event) {
        log.info("[demo-listener] AttendanceRecorded employeeId={} eventType={} captureMethod={} at={}",
                event.employeeId(), event.eventType(), event.captureMethod(), event.eventTime());
    }
}
