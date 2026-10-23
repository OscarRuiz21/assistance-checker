package com.assistance;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Arranca el contexto completo (JPA + Flyway + Postgres real), así que
 * requiere Docker (Testcontainers) o una instancia de Postgres accesible por
 * {@code DB_URL}. Se excluye del task {@code test} normal (ver
 * {@code excludeTags("integration")} en build.gradle.kts) y solo corre con
 * {@code ./gradlew integrationTest}.
 */
@Tag("integration")
@SpringBootTest
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
