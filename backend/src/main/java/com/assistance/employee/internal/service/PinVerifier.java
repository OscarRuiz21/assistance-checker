package com.assistance.employee.internal.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Verifica un PIN contra el hash bcrypt almacenado, sin exponer el hash.
 * Usa pgcrypto.crypt() en SQL para que el cómputo del bcrypt corra en el
 * servidor de DB, no en el JVM (más simple, sin dependencia de jbcrypt).
 */
@Component
public class PinVerifier {

    private final JdbcTemplate jdbc;

    public PinVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean verify(UUID employeeId, String pin) {
        if (pin == null || pin.isBlank()) return false;
        Boolean ok = jdbc.queryForObject(
                "SELECT pin_hash = crypt(?, pin_hash) FROM employee WHERE id = ?",
                Boolean.class, pin, employeeId);
        return ok != null && ok;
    }
}
