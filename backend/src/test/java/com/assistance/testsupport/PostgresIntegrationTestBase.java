package com.assistance.testsupport;

import com.assistance.shared.api.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base para tests de integración que necesitan una Postgres 16 real
 * (Testcontainers) y el rol de aplicación sin privilegios de superusuario
 * exactamente como en producción (ver V3__tenant_rls.sql): Flyway migra con
 * el rol "superusuario" del contenedor (equivalente a {@code assistance}),
 * la app (y estos tests) se conectan con {@code assistance_app}
 * (NOSUPERUSER NOBYPASSRLS) — así que si una política RLS tiene un hueco,
 * el test lo detecta de la misma forma que lo detectaría producción.
 *
 * <p>Todos los tests marcados {@code @Tag("integration")} quedan excluidos
 * de {@code ./gradlew test} (ver build.gradle.kts) y solo corren con
 * {@code ./gradlew integrationTest} (requiere Docker).
 *
 * <p>Subclases NO deben asumir un estado de tenant en {@link TenantContext}:
 * se limpia antes/después de cada test para que un test que olvide hacer
 * {@code runAs}/{@code clear} no contamine al siguiente.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class PostgresIntegrationTestBase {

    protected static final String APP_DB_USER = "assistance_app";
    protected static final String APP_DB_PASSWORD = "integration_test_app_password";

    /** Clave AES de prueba (cualquier passphrase sirve: PiiCipher la pasa por SHA-256). */
    protected static final String TEST_PII_KEY = "integration-test-pii-key-not-for-prod-use";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("assistance")
                    .withUsername("assistance")
                    .withPassword("integration_test_migration_password");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // spring.datasource.* → rol de APLICACIÓN (como en runtime real): si
        // una tabla/columna/función le falta un GRANT, el test falla aquí,
        // no en producción.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_DB_USER);
        registry.add("spring.datasource.password", () -> APP_DB_PASSWORD);

        // spring.flyway.* → rol de MIGRACIÓN (superusuario del contenedor),
        // igual que application.yml (DB_MIGRATION_USER/PASSWORD).
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.app_db_user", () -> APP_DB_USER);
        registry.add("spring.flyway.placeholders.app_db_password", () -> APP_DB_PASSWORD);

        registry.add("app.pii.key", () -> TEST_PII_KEY);
        // Jobs programados (retención) apagados salvo que un test específico
        // los habilite explícitamente con @TestPropertySource.
        registry.add("app.retention.enabled", () -> "false");
    }

    @org.springframework.beans.factory.annotation.Autowired
    protected JdbcTemplate jdbc;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }
}
