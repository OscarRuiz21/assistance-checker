package com.assistance.compliance.internal.service;

import com.assistance.shared.api.TenantContext;
import com.assistance.shared.api.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fase 3.3: job nocturno de retención de {@code event_raw} según
 * {@code tenant.retention_config.events_years} (LFT Art. 804: mínimo 5 años
 * de registros laborales). Archiva (nunca borra sin archivar antes) vía la
 * función SQL {@code archive_and_purge_events(...)} (SECURITY DEFINER, ver
 * V7__retention_config.sql), que inserta en {@code event_raw_archive} y solo
 * entonces borra de {@code event_raw} dentro de la misma transacción de BD,
 * saltando el guard append-only de V4 únicamente para esa operación.
 *
 * <p><b>Deshabilitado por defecto</b>: {@code @ConditionalOnProperty} sin
 * {@code matchIfMissing} exige {@code app.retention.enabled=true}
 * explícito; si la propiedad no existe (caso por defecto, no se toca
 * {@code application*.yml} desde este módulo), el bean ni se registra y el
 * {@code @Scheduled} nunca corre. {@code @EnableScheduling} ya está puesto a
 * nivel de aplicación en {@code BackendApplication} (lo usa también
 * {@code EventStreamHub}), así que no hace falta agregarlo aquí.
 *
 * <p>Cada tenant se procesa con {@link TenantContext#runAs(TenantScope, java.util.function.Supplier)}
 * (scope de sistema, sin usuario/kiosko) para que la conexión JDBC que usa
 * la función lleve el {@code app.tenant_id} correcto — aunque la función en
 * sí filtra explícitamente por {@code p_tenant_id}, mantener el GUC
 * consistente evita sorpresas si en el futuro alguna otra consulta dentro
 * del mismo scope depende de RLS.
 */
@Service
@ConditionalOnProperty(prefix = "app.retention", name = "enabled", havingValue = "true")
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final JdbcTemplate jdbc;

    public RetentionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        log.info("RetentionService habilitado (app.retention.enabled=true): el job de archivado "
                + "de event_raw correrá según app.retention.cron");
    }

    @Scheduled(cron = "${app.retention.cron:0 30 2 * * *}")
    public void archiveAllTenants() {
        List<Map<String, Object>> tenants = jdbc.queryForList("SELECT * FROM list_tenant_ids_for_retention()");
        log.info("Retention: iniciando corrida para {} tenant(s)", tenants.size());
        int totalArchived = 0;
        for (Map<String, Object> row : tenants) {
            UUID tenantId = (UUID) row.get("tenant_id");
            int eventsYears = ((Number) row.get("events_years")).intValue();
            try {
                int archived = TenantContext.runAs(TenantScope.system(tenantId),
                        () -> archiveTenant(tenantId, eventsYears));
                totalArchived += archived;
            } catch (Exception e) {
                // Un tenant con error no debe tumbar la corrida de los demás.
                log.error("Retention: fallo archivando tenant {} (continuando con el resto)", tenantId, e);
            }
        }
        log.info("Retention: corrida terminada, {} eventos archivados en total", totalArchived);
    }

    private int archiveTenant(UUID tenantId, int eventsYears) {
        Instant cutoff = LocalDate.now(ZoneOffset.UTC)
                .minusYears(eventsYears)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        Integer archived = jdbc.queryForObject(
                "SELECT archive_and_purge_events(?::uuid, ?)",
                Integer.class, tenantId.toString(), Timestamp.from(cutoff));
        int count = archived == null ? 0 : archived;
        if (count > 0) {
            log.info("Retention: tenant {} → {} eventos archivados (cutoff={}, events_years={})",
                    tenantId, count, cutoff, eventsYears);
        }
        return count;
    }
}
