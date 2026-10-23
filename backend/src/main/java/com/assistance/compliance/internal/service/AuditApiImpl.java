package com.assistance.compliance.internal.service;

import com.assistance.compliance.api.AuditApi;
import com.assistance.shared.api.TenantContext;
import com.assistance.shared.api.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Inserta directamente en {@code audit_log} (append-only, ver
 * V6__audit_log.sql). No usa JPA a propósito: es una tabla de solo-inserción
 * de alto volumen potencial y no necesita identidad gestionada ni dirty
 * checking.
 */
@Service
public class AuditApiImpl implements AuditApi {

    private static final Logger log = LoggerFactory.getLogger(AuditApiImpl.class);

    private static final String INSERT_SQL = """
            INSERT INTO audit_log
                (tenant_id, actor_user_id, actor_kiosko_id, action, entity_type, entity_id, after, occurred_at)
            VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?::uuid, ?::jsonb, now())
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditApiImpl(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(String action, String entityType, UUID entityId, Map<String, Object> details) {
        TenantScope scope = TenantContext.require();
        String detailsJson = toJson(action, details);
        jdbc.update(INSERT_SQL,
                scope.tenantId().toString(),
                scope.userId() != null ? scope.userId().toString() : null,
                scope.kioskoId() != null ? scope.kioskoId().toString() : null,
                action,
                entityType,
                entityId != null ? entityId.toString() : null,
                detailsJson);
    }

    private String toJson(String action, Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return "{}";
        }
        try {
            // Nunca debe llegar PII aquí; esto es solo una salvaguarda de forma,
            // no reemplaza la disciplina de los llamadores.
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            log.warn("No se pudo serializar detalle de auditoría para action={}", action, e);
            return "{}";
        }
    }
}
