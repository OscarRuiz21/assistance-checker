package com.assistance.attendance.internal.web;

import com.assistance.attendance.api.AttendanceApi;
import com.assistance.attendance.api.EventView;
import com.assistance.attendance.internal.web.dto.CreateEventDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private static final ZoneId TZ_MX = ZoneId.of("America/Mexico_City");
    private static final DateTimeFormatter MX_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(TZ_MX);

    private final AttendanceApi attendance;

    public EventController(AttendanceApi attendance) {
        this.attendance = attendance;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventView create(@Valid @RequestBody CreateEventDto dto) {
        return attendance.record(dto.toCommand());
    }

    @GetMapping
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
    public ResponseEntity<String> exportCsv(
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        LocalDate fromD = from != null ? from : LocalDate.now(TZ_MX).minusDays(30);
        LocalDate toD = to != null ? to : LocalDate.now(TZ_MX);
        Instant fromI = fromD.atStartOfDay(TZ_MX).toInstant();
        Instant toI = toD.plusDays(1).atStartOfDay(TZ_MX).toInstant();

        var rows = attendance.list(employeeId, fromI, toI, 10_000);
        StringBuilder sb = new StringBuilder();
        sb.append("﻿"); // BOM para Excel
        sb.append("Empleado;Numero;Tipo;Metodo;Fecha y hora;Recibido\n");
        for (EventView r : rows) {
            sb.append(escape(r.employeeName())).append(';')
                    .append(escape(r.employeeNumber())).append(';')
                    .append(r.eventType().dbValue()).append(';')
                    .append(r.captureMethod().dbValue()).append(';')
                    .append(MX_FMT.format(r.eventTime())).append(';')
                    .append(MX_FMT.format(r.receivedAt())).append('\n');
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
