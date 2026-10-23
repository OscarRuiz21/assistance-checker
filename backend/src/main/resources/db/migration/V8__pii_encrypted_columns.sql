-- Fase 3.4 (doc 02/03): columnas cifradas para CURP/RFC/NSS, en paralelo a
-- las columnas en claro heredadas del schema demo (V1). La clave de cifrado
-- (app.pii.key / APP_PII_KEY) es un secreto de aplicación que esta
-- migración SQL, corriendo con el rol de migración/superusuario, no conoce
-- ni puede usar — por eso solo agrega las columnas bytea; el llenado real
-- ocurre en V9__EncryptExistingPiiData.java (migración Java con
-- PiiCipher inyectado por Spring), que también limpia curp/rfc/nss.
--
-- Las columnas en claro NO se eliminan en esta migración: se dejan vivas
-- (NULL después de V9) para no romper un rollback accidental a una versión
-- de la entidad Employee anterior a este cambio; se podrán DROP en una
-- migración futura una vez confirmado que ningún código las lee.

ALTER TABLE employee
    ADD COLUMN curp_enc BYTEA,
    ADD COLUMN rfc_enc BYTEA,
    ADD COLUMN nss_enc BYTEA;

COMMENT ON COLUMN employee.curp_enc IS 'AES-256-GCM: IV(12 bytes) || ciphertext+tag. Ver PiiCipher.';
COMMENT ON COLUMN employee.rfc_enc IS 'AES-256-GCM: IV(12 bytes) || ciphertext+tag. Ver PiiCipher.';
COMMENT ON COLUMN employee.nss_enc IS 'AES-256-GCM: IV(12 bytes) || ciphertext+tag. Ver PiiCipher.';

COMMENT ON COLUMN employee.curp IS 'DEPRECATED: en claro, solo legacy. Cifrado real vive en curp_enc desde V9. No volver a escribir aquí.';
COMMENT ON COLUMN employee.rfc IS 'DEPRECATED: en claro, solo legacy. Cifrado real vive en rfc_enc desde V9. No volver a escribir aquí.';
COMMENT ON COLUMN employee.nss IS 'DEPRECADO: en claro, solo legacy. Cifrado real vive en nss_enc desde V9. No volver a escribir aquí.';
