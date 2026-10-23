package com.assistance.attendance.internal.web;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/events")
public class EventStreamController {

    private final EventStreamHub hub;

    public EventStreamController(EventStreamHub hub) {
        this.hub = hub;
    }

    // SSE con EventSource nativo no soporta headers, así que no podría mandar
    // el Authorization: Bearer <jwt>. En vez de inventar un esquema de ticket
    // de un solo uso, el frontend usa fetch() con ReadableStream (ver
    // frontend/src/lib/sse.ts) para poder mandar el header normal — este
    // endpoint se protege exactamente igual que cualquier otro GET de admin.
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('super_admin','tenant_admin','rh','supervisor')")
    public SseEmitter stream() {
        return hub.register();
    }
}
