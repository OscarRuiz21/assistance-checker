package com.assistance.shared;

import com.assistance.shared.internal.config.ProdFailFastValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ProdFailFastValidator} debe abortar el arranque en prod si falta
 * cualquier secreto obligatorio, o si {@code APP_JWT_SECRET} está presente
 * (en prod SOLO Cognito firma tokens de usuario). Se instancia directo, sin
 * contexto Spring: el componente no tiene más dependencias que los valores
 * inyectados por constructor.
 */
class ProdFailFastValidatorTest {

    private static final String DB_PASSWORD = "s3cret-db-password";
    private static final String PII_KEY = "s3cret-pii-key";
    private static final String CORS_ORIGINS = "https://admin.example.mx";
    private static final String ISSUER_URI = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_abc123";

    @Test
    void startsCleanlyWhenAllRequiredSecretsArePresentAndNoJwtSecret() {
        var validator = new ProdFailFastValidator(DB_PASSWORD, PII_KEY, CORS_ORIGINS, ISSUER_URI, "");

        assertThatCode(() -> validator.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    void failsWhenDbPasswordIsBlank() {
        var validator = new ProdFailFastValidator("", PII_KEY, CORS_ORIGINS, ISSUER_URI, "");

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_APP_PASSWORD");
    }

    @Test
    void failsWhenPiiKeyIsMissing() {
        var validator = new ProdFailFastValidator(DB_PASSWORD, null, CORS_ORIGINS, ISSUER_URI, "");

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_PII_KEY");
    }

    @Test
    void failsWhenCorsOriginsAreMissing() {
        var validator = new ProdFailFastValidator(DB_PASSWORD, PII_KEY, "  ", ISSUER_URI, "");

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS_ORIGINS");
    }

    @Test
    void failsWhenAuthIssuerUriIsMissing() {
        var validator = new ProdFailFastValidator(DB_PASSWORD, PII_KEY, CORS_ORIGINS, "", "");

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_ISSUER_URI");
    }

    @Test
    void failsWhenJwtSecretIsSetInProd() {
        var validator = new ProdFailFastValidator(
                DB_PASSWORD, PII_KEY, CORS_ORIGINS, ISSUER_URI, "someone-left-a-jwt-secret-set");

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_JWT_SECRET");
    }

    @Test
    void reportsAllErrorsTogether() {
        var validator = new ProdFailFastValidator(null, null, null, null, "set");

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .hasMessageContaining("DB_APP_PASSWORD")
                .hasMessageContaining("APP_PII_KEY")
                .hasMessageContaining("CORS_ORIGINS")
                .hasMessageContaining("AUTH_ISSUER_URI")
                .hasMessageContaining("APP_JWT_SECRET");
    }
}
