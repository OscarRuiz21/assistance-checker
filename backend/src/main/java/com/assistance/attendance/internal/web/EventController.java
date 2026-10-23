package com.assistance.attendance.internal.web;

import com.assistance.attendance.api.AttendanceApi;
import com.assistance.attendance.api.EventView;
import com.assistance.attendance.internal.service.TimezoneResolver;
import com.assistance.attendance.internal.service.exception.InvalidPinException;
import com.assistance.attendance.internal.web.dto.CreateEventDto;
import com.assistance.shared.api.ApiException;
import com.assistance.shared.api.RateLimiter;
import com.assistance.shared.api.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private static final DateTimeFormatter MX_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Lockout de PIN por kiosko: máximo 20 fallos en 15 min (ver
    // docs/16-readiness-plan.md Fase 1.5). El lockout por EMPLEADO vive en
    // PinVerifier, que sí conoce el employeeId directamente; este endpoint
    // es el único punto con acceso a kioskoId + resultado de la verificación
    // de PIN al mismo tiempo, así que el contador por kiosko vive aquí.
    private static final int MAX_PIN_FAILS_PER_KIOSKO = 20;
    private final RateLimiter.Bucket kioskoPinFails = RateLimiter.windowOf(Duration.ofMinutes(15));

    private final AttendanceApi attendance;
    private final TimezoneResolver timezoneResolver;

    public EventController(AttendanceApi attendance, TimezoneResolver timezoneResolver) {
        this.attendance = attendance;
        this.timezoneResolver = timezoneResolver;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventView create(@Valid @RequestBody CreateEventDto dto) {
        UUID kioskoId = dto.kioskoId();
        if (kioskoId != null && kioskoPinFails.current(kioskoId.toString()) >= MAX_PIN_FAILS_PER_KIOSKO) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PIN_LOCKED",
                    "Demasiados PIN incorrectos en este kiosko. Intente de nuevo en unos minutos");
        }
        try {
            return attendance.record(dto.toCommand());
        } catch (InvalidPinException e) {
            if (kioskoId != null) {
                kioskoPinFails.increment(kioskoId.toString());
            }
            throw e;
        }
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('super_admin','tenant_admin','rh','supervisor')")
    public List<EventView> list(
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "50") int limit) {
        Instant fromR = from != null ? from : Instant.now().minusSeconds(7L * 24 * 3600);
        Instant toR = to != null ? to : Instant.now().plusSeconds(60);
        return attendance.list(employeeId, fromR, toR, limit);
    }

    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    @PreAuthorize("hasAnyRole('tenant_admin','rh')")
    public ResponseEntity<String> exportCsv(
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        ZoneId tz = ZoneId.of(timezoneResolver.resolve(TenantContext.require()));
        LocalDate fromD = from != null ? from : LocalDate.now(tz).minusDays(30);
        LocalDate toD = to != null ? to : LocalDate.now(tz);
        Instant fromI = fromD.atStartOfDay(tz).toInstant();
        Instant toI = toD.plusDays(1).atStartOfDay(tz).toInstant();

        DateTimeFormatter fmt = MX_FMT.withZone(tz);
        var rows = attendance.list(employeeId, fromI, toI, 10_000);
        StringBuilder sb = new StringBuilder();
        sb.append("﻿"); // BOM para Excel
        sb.append("Empleado;Numero;Tipo;Metodo;Fecha y hora;Recibido\n");
        for (EventView r : rows) {
            sb.append(escape(r.employeeName())).append(';')
                    .append(escape(r.employeeNumber())).append(';')
                    .append(r.eventType().dbValue()).append(';')
                    .append(r.captureMethod().dbValue()).append(';')
                    .append(fmt.format(r.eventTime())).append(';')
                    .append(fmt.format(r.receivedAt())).append('\n');
        }

        String suffix = employeeId != null ? "_emp-" + employeeId.toString().substring(0, 8) : "";
        String filename = "asistencia" + suffix + "_" + fromD + "_" + toD + ".csv";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(sb.toString());
    }

    private String escape(String s) {
        if (s == null) return "";
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
