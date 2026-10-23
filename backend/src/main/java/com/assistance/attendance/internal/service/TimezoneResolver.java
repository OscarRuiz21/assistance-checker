package com.assistance.attendance.internal.service;

import com.assistance.shared.api.TenantScope;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Resuelve la zona horaria efectiva para un {@link TenantScope}, en vez de
 * usar {@code 'America/Mexico_City'} hardcodeado (requisito Fase 2.4).
 *
 * <p>Regla: si el scope está acotado a una sola planta, se usa
 * {@code plant.timezone} (si no es null); si no, o si hay varias/ninguna
 * planta en el scope, se usa {@code tenant.timezone}. Como último fallback
 * (dato corrupto/ausente) se usa {@code America/Mexico_City}, que es el
 * default también a nivel de columna en la base de datos.
 */
@Component
public class TimezoneResolver {

    static final String FALLBACK_TZ = "America/Mexico_City";

    private final JdbcTemplate jdbc;

    public TimezoneResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String resolve(TenantScope scope) {
        Set<UUID> plantIds = scope.plantIds();
        if (plantIds.size() == 1) {
            String plantTz = queryOne(
                    "SELECT COALESCE(timezone, (SELECT timezone FROM tenant WHERE id = ?::uuid)) "
                            + "FROM plant WHERE id = ?::uuid AND tenant_id = ?::uuid",
                    plantIds.iterator().next().toString(), scope.tenantId().toString(), scope.tenantId().toString());
            if (plantTz != null) {
                return plantTz;
            }
        }
        String tenantTz = queryOne("SELECT timezone FROM tenant WHERE id = ?::uuid", scope.tenantId().toString());
        return tenantTz != null ? tenantTz : FALLBACK_TZ;
    }

    private String queryOne(String sql, Object... args) {
        try {
            return jdbc.queryForObject(sql, String.class, args);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
