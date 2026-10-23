package com.assistance.employee.internal.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Convierte {@code String} (CURP/RFC/NSS en claro, solo en memoria Java) a
 * {@code bytea} cifrado (AES-256-GCM, ver {@link PiiCipher}) y viceversa.
 *
 * <p>Se registra como bean de Spring para que Hibernate pueda inyectarle
 * {@link PiiCipher} vía el bean container de Spring (soportado de forma
 * nativa por Spring Boot). Como defensa contra el caso en que Hibernate
 * decida instanciarlo por su cuenta con el constructor sin argumentos (p.
 * ej. en algún escenario de bootstrap atípico), se guarda también una
 * referencia estática que cualquier instancia puede usar — se fija la
 * primera vez que Spring construye el bean, lo cual siempre ocurre antes de
 * que cualquier entidad se persista o lea.
 */
@Converter
@Component
public class PiiAttributeConverter implements AttributeConverter<String, byte[]> {

    private static volatile PiiCipher sharedCipher;

    private final PiiCipher cipher;

    public PiiAttributeConverter(PiiCipher cipher) {
        this.cipher = cipher;
        sharedCipher = cipher;
    }

    @Override
    public byte[] convertToDatabaseColumn(String attribute) {
        return resolveCipher().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(byte[] dbData) {
        return resolveCipher().decrypt(dbData);
    }

    private PiiCipher resolveCipher() {
        PiiCipher resolved = this.cipher != null ? this.cipher : sharedCipher;
        if (resolved == null) {
            throw new IllegalStateException(
                    "PiiCipher no inicializado todavía: se intentó (des)cifrar PII antes de que "
                            + "el contexto de Spring terminara de arrancar");
        }
        return resolved;
    }
}
