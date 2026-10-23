package com.assistance.employee.internal.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unitario (sin Docker/BD real): round-trip de cifrado AES-256-GCM para
 * columnas de PII (Fase 3.4). No depende de Spring ni de app.pii.key en
 * properties: se instancia {@link PiiCipher} directamente con claves fijas
 * de prueba.
 */
class PiiCipherTest {

    @Test
    void encryptDecrypt_roundTripRecuperaElValorOriginal() {
        PiiCipher cipher = new PiiCipher("clave-de-prueba-32-bytes-o-mas!!");
        String curp = "GOMC850101HDFNRS05";

        byte[] encrypted = cipher.encrypt(curp);
        String decrypted = cipher.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(curp);
    }

    @Test
    void encrypt_nuncaDejaElValorEnClaroVisibleEnElCiphertext() {
        PiiCipher cipher = new PiiCipher("otra-clave-de-prueba-distinta");
        String rfc = "GOMC850101AB3";

        byte[] encrypted = cipher.encrypt(rfc);

        String asLatin1 = new String(encrypted, StandardCharsets.ISO_8859_1);
        assertThat(asLatin1).doesNotContain(rfc);
    }

    @Test
    void encrypt_esNoDeterminista_mismoValorProduceCiphertextsDistintos() {
        PiiCipher cipher = new PiiCipher("clave-de-prueba-32-bytes-o-mas!!");
        String nss = "12345678901";

        byte[] first = cipher.encrypt(nss);
        byte[] second = cipher.encrypt(nss);

        // IV aleatorio por llamada (ver formato IV||ciphertext+tag): dos
        // cifrados del mismo valor no deben coincidir byte a byte.
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(nss);
        assertThat(cipher.decrypt(second)).isEqualTo(nss);
    }

    @Test
    void decrypt_conLlaveDistinta_fallaOProduceBasura() {
        PiiCipher encryptor = new PiiCipher("clave-A-para-cifrar-datos-reales");
        PiiCipher wrongDecryptor = new PiiCipher("clave-B-completamente-diferente");
        byte[] encrypted = encryptor.encrypt("AAAA800101HDFLNS09");

        // AES-GCM es autenticado: con la clave equivocada, el tag falla y se
        // lanza excepción (nunca "éxito silencioso" devolviendo datos
        // corruptos como si fueran válidos).
        assertThatThrownBy(() -> wrongDecryptor.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void encryptDecrypt_nullEsNull() {
        PiiCipher cipher = new PiiCipher("clave-de-prueba-32-bytes-o-mas!!");

        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }
}
