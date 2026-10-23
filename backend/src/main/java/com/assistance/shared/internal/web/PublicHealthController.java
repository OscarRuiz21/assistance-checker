package com.assistance.shared.internal.web;

import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health check en el puerto PÚBLICO ({@code server.port}), con el mismo path
 * que el real Actuator ({@code /actuator/health}) pero fuera del contexto de
 * management. Necesario porque el resto de Actuator (metrics, prometheus,
 * modulith, env) vive exclusivamente en {@code management.server.port}
 * (contrato Fase 0: "actuator público solo health, resto en management
 * port"), pero el ALB / healthcheck de ECS de la Fase 4
 * ({@code infra/modules/app/alb.tf}) golpea {@code /actuator/health} sobre el
 * puerto del contenedor (el público), no el de management — y Spring Boot,
 * cuando {@code management.server.port} difiere de {@code server.port}, deja
 * de registrar CUALQUIER endpoint de Actuator (incluido health) en el puerto
 * principal.
 *
 * Esto delega al {@link HealthEndpoint} real (mismos indicadores: DB, disco,
 * etc.) para que el health check público refleje el estado real de la app, no
 * solo "el servidor HTTP responde". No expone detalles ({@code
 * show-details: never} aplica igual aquí, solo se expone el status).
 */
@RestController
public class PublicHealthController {

    private final HealthEndpoint healthEndpoint;

    public PublicHealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/actuator/health")
    public ResponseEntity<Map<String, String>> health() {
        HealthComponent health = healthEndpoint.health();
        Status status = health.getStatus();
        HttpStatus httpStatus = Status.UP.equals(status) || Status.UNKNOWN.equals(status)
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(Map.of("status", status.getCode()));
    }
}
