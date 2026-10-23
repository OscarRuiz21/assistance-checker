package com.assistance.employee.internal.migration;

import com.assistance.employee.internal.crypto.PiiCipher;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Migración Flyway basada en Java (no SQL): cifra el CURP/RFC/NSS existentes
 * (sembrados en texto plano por V2__seed_demo.sql) hacia las columnas
 * {@code curp_enc}/{@code rfc_enc}/{@code nss_enc} creadas por
 * V8__pii_encrypted_columns.sql, y limpia las columnas en claro.
 *
 * <p><b>Por qué una migración Java y no SQL</b>: la clave de cifrado
 * ({@code app.pii.key} / {@code APP_PII_KEY}) es un secreto de aplicación
 * que Flyway (que corre con el rol de migración/superusuario, sin acceso al
 * contexto de Spring) no puede ver. Al registrar esta clase como
 * {@code @Component} que implementa {@code JavaMigration}, Spring Boot la
 * agrega automáticamente a {@code Flyway.javaMigrations(...)} — así que sí
 * corre dentro del ciclo de vida de Flyway (en su propio paso de versión,
 * V9, después de V8) pero con {@link PiiCipher} completamente inyectado por
 * Spring, incluida la clave real.
 *
 * <p>Idempotente: solo toca filas donde las columnas en claro siguen no-NULL;
 * reintentar esta migración (p. ej. tras un fallo parcial) es seguro.
 *
 * <p>Si {@code app.pii.key} no está configurada, {@link PiiCipher} cifra con
 * una clave de relleno insegura (ver su javadoc) — la migración igual corre
 * para no bloquear el arranque en dev, pero deja un WARN. No se recomienda
 * ejecutar esta migración en un ambiente con datos reales sin
 * {@code APP_PII_KEY} configurada de antemano.
 */
@Component
public class V9__EncryptExistingPiiData extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V9__EncryptExistingPiiData.class);

    private final PiiCipher cipher;

    public V9__EncryptExistingPiiData(PiiCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<UUID> ids = new ArrayList<>();
        List<byte[]> curpEnc = new ArrayList<>();
        List<byte[]> rfcEnc = new ArrayList<>();
        List<byte[]> nssEnc = new ArrayList<>();

        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, curp, rfc, nss FROM employee "
                        + "WHERE curp IS NOT NULL OR rfc IS NOT NULL OR nss IS NOT NULL");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                ids.add(UUID.fromString(rs.getString("id")));
                curpEnc.add(encryptOrNull(rs.getString("curp")));
                rfcEnc.add(encryptOrNull(rs.getString("rfc")));
                nssEnc.add(encryptOrNull(rs.getString("nss")));
            }
        }

        if (ids.isEmpty()) {
            log.info("V9: no hay CURP/RFC/NSS en claro pendientes de cifrar");
            return;
        }

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE employee SET curp_enc = ?, rfc_enc = ?, nss_enc = ?, "
                        + "curp = NULL, rfc = NULL, nss = NULL WHERE id = ?::uuid")) {
            for (int i = 0; i < ids.size(); i++) {
                update.setBytes(1, curpEnc.get(i));
                update.setBytes(2, rfcEnc.get(i));
                update.setBytes(3, nssEnc.get(i));
                update.setString(4, ids.get(i).toString());
                update.addBatch();
            }
            update.executeBatch();
        }

        // No se loguea el conteo con detalle que pudiera asociarse a PII real,
        // solo la cantidad de filas tocadas.
        log.info("V9: cifradas y limpiadas columnas PII en claro de {} empleados", ids.size());
    }

    private byte[] encryptOrNull(String plaintext) {
        return plaintext == null ? null : cipher.encrypt(plaintext);
    }

    /** Evita colisión con el checksum por defecto basado en el jar/clase. */
    @Override
    public Integer getChecksum() {
        return 1;
    }
}
