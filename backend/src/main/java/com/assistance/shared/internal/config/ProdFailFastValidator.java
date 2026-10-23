package com.assistance.shared.internal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Fail-fast: si el perfil {@code prod} está activo y falta un secreto
 * obligatorio (o sobra uno que NO debería existir en prod), aborta el
 * arranque con un mensaje claro en vez de levantar un backend inseguro.
 * Corre antes que casi todo ({@code Ordered.HIGHEST_PRECEDENCE}).
 */
@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProdFailFastValidator implements ApplicationRunner {

    private final String dbAppPassword;
    private final String piiKey;
    private final String corsOrigins;
    private final String authIssuerUri;
    private final String jwtSecret;

    public ProdFailFastValidator(
            @Value("${spring.datasource.password:}") String dbAppPassword,
            @Value("${app.pii.key:}") String piiKey,
            @Value("${app.cors.allowed-origins:}") String corsOrigins,
            @Value("${app.auth.issuer-uri:}") String authIssuerUri,
            @Value("${app.auth.jwt-secret:}") String jwtSecret) {
        this.dbAppPassword = dbAppPassword;
        this.piiKey = piiKey;
        this.corsOrigins = corsOrigins;
        this.authIssuerUri = authIssuerUri;
        this.jwtSecret = jwtSecret;
    }

    @Override
    public void run(ApplicationArguments args) {
        StringBuilder errors = new StringBuilder();

        requireNonBlank(errors, "DB_APP_PASSWORD", dbAppPassword);
        requireNonBlank(errors, "APP_PII_KEY", piiKey);
        requireNonBlank(errors, "CORS_ORIGINS", corsOrigins);
        requireNonBlank(errors, "AUTH_ISSUER_URI", authIssuerUri);

        if (jwtSecret != null && !jwtSecret.isBlank()) {
            errors.append("- APP_JWT_SECRET no debe estar seteado en prod: en producción SOLO Cognito "
                    + "(AUTH_ISSUER_URI) firma tokens de usuario.\n");
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Configuración de producción inválida, abortando arranque:\n" + errors);
        }
    }

    private void requireNonBlank(StringBuilder errors, String envVarName, String value) {
        if (value == null || value.isBlank()) {
            errors.append("- ").append(envVarName).append(" es obligatorio en el perfil prod y está vacío.\n");
        }
    }
}
