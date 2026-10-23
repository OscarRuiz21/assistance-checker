package com.assistance.employee.internal.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Cifrado simétrico AES-256-GCM para columnas de PII (CURP/RFC/NSS) en la
 * capa de aplicación (Fase 3.10). Alternativa elegida frente a
 * {@code pgp_sym_encrypt}/{@code pgp_sym_decrypt} de pgcrypto porque la
 * clave nunca viaja a la base de datos ni queda en una migración SQL — vive
 * solo en {@code app.pii.key} (env {@code APP_PII_KEY}), consistente con
 * "nunca loguear PII" y con que las migraciones Flyway SQL no tienen acceso
 * a secretos de aplicación.
 *
 * <p>Formato de columna: {@code IV(12 bytes) || ciphertext+tag(GCM)}.
 *
 * <p><b>Clave</b>: se deriva con SHA-256 del valor de {@code app.pii.key}
 * (acepta passphrase de cualquier longitud o un secreto ya en base64/hex, da
 * igual: el hash siempre produce 32 bytes válidos para AES-256). Si la
 * propiedad no está configurada (p. ej. en un entorno de desarrollo sin
 * {@code APP_PII_KEY}), se usa una clave de relleno fija y se loguea un
 * WARN fuerte — NUNCA debe pasar en demo/staging/prod con datos reales;
 * AGENTE A/C deben asegurar que {@code APP_PII_KEY} esté seteada fuera de
 * `local`.
 */
@Component
public class PiiCipher {

    private static final Logger log = LoggerFactory.getLogger(PiiCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String INSECURE_FALLBACK_KEY =
            "INSECURE-DEV-FALLBACK-DO-NOT-USE-WITH-REAL-PII-SET-APP_PII_KEY";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public PiiCipher(@Value("${app.pii.key:}") String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            log.warn("app.pii.key (APP_PII_KEY) no está configurada. Usando clave de relleno "
                    + "INSEGURA solo apta para desarrollo local sin datos reales. "
                    + "Cualquier dato cifrado con esta clave será irrecuperable si se configura "
                    + "una clave real después.");
            rawKey = INSECURE_FALLBACK_KEY;
        }
        this.key = new SecretKeySpec(sha256(rawKey), "AES");
    }

    public byte[] encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return out;
        } catch (GeneralSecurityException e) {
            // Nunca incluir el valor en claro en el mensaje de excepción/log.
            throw new IllegalStateException("No se pudo cifrar un campo de PII", e);
        }
    }

    public String decrypt(byte[] data) {
        if (data == null) {
            return null;
        }
        if (data.length < IV_LENGTH_BYTES) {
            throw new IllegalArgumentException("Dato cifrado con formato inválido (demasiado corto)");
        }
        try {
            byte[] iv = Arrays.copyOfRange(data, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(data, IV_LENGTH_BYTES, data.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo descifrar un campo de PII", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }
}
