package com.assistance.employee.internal.service;

import com.assistance.shared.api.ApiException;
import com.assistance.shared.api.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Verifica un PIN contra el hash bcrypt almacenado, sin exponer el hash.
 * Usa pgcrypto.crypt() en SQL para que el cómputo del bcrypt corra en el
 * servidor de DB, no en el JVM (más simple, sin dependencia de jbcrypt).
 *
 * Lockout: máximo 5 fallos por empleado en 15 min (docs/16-readiness-plan.md
 * Fase 1.5) — después de eso, 429 PIN_LOCKED sin siquiera tocar la DB. El
 * contador vive en memoria (Caffeine); en multi-instancia habría que
 * moverlo a Redis o una tabla (ver RateLimiter para la nota completa).
 */
@Component
public class PinVerifier {

    private static final int MAX_FAILS_PER_EMPLOYEE = 5;

    private final JdbcTemplate jdbc;
    private final RateLimiter.Bucket employeeFails = RateLimiter.windowOf(Duration.ofMinutes(15));

    public PinVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean verify(UUID employeeId, String pin) {
        if (employeeFails.current(employeeId.toString()) >= MAX_FAILS_PER_EMPLOYEE) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PIN_LOCKED",
                    "Demasiados PIN incorrectos para este empleado. Intente de nuevo en unos minutos");
        }
        if (pin == null || pin.isBlank()) return false;
        Boolean ok = jdbc.queryForObject(
                "SELECT pin_hash = crypt(?, pin_hash) FROM employee WHERE id = ?",
                Boolean.class, pin, employeeId);
        boolean result = ok != null && ok;
        if (result) {
            employeeFails.reset(employeeId.toString());
        } else {
            employeeFails.increment(employeeId.toString());
        }
        return result;
    }
}
