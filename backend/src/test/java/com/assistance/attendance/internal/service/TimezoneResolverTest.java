package com.assistance.attendance.internal.service;

import com.assistance.shared.api.TenantScope;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unitario (sin Docker/BD real): verifica la regla de resolución de
 * TimezoneResolver (Fase 2.4, requisito "usar plant.timezone en todos los
 * cálculos, nunca 'America/Mexico_City' hardcodeado salvo fallback
 * documentado"). Se evita Mockito para el varargs de
 * {@code JdbcTemplate.queryForObject} (frágil de mockear) con un fake
 * mínimo que solo sobreescribe ese método.
 */
class TimezoneResolverTest {

    @Test
    void unaSolaPlantaEnScope_usaTimezoneDeLaPlanta() {
        TimezoneResolver resolver = new TimezoneResolver(fakeReturning("America/Tijuana"));
        TenantScope scope = new TenantScope(
                UUID.randomUUID(), null, UUID.randomUUID(), Set.of(UUID.randomUUID()), Set.of());

        assertThat(resolver.resolve(scope)).isEqualTo("America/Tijuana");
    }

    @Test
    void sinPlantaEnScope_usaTimezoneDelTenant() {
        TimezoneResolver resolver = new TimezoneResolver(fakeReturning("America/Cancun"));
        TenantScope scope = new TenantScope(
                UUID.randomUUID(), UUID.randomUUID(), null, Set.of(), Set.of("tenant_admin"));

        assertThat(resolver.resolve(scope)).isEqualTo("America/Cancun");
    }

    @Test
    void variasPlantasEnScope_usaTimezoneDelTenant() {
        TimezoneResolver resolver = new TimezoneResolver(fakeReturning("America/Monterrey"));
        TenantScope scope = new TenantScope(
                UUID.randomUUID(), UUID.randomUUID(), null,
                Set.of(UUID.randomUUID(), UUID.randomUUID()), Set.of("tenant_admin"));

        assertThat(resolver.resolve(scope)).isEqualTo("America/Monterrey");
    }

    @Test
    void sinDatosEnBd_usaFallbackDocumentado() {
        TimezoneResolver resolver = new TimezoneResolver(fakeThrowingEmpty());
        TenantScope scope = new TenantScope(
                UUID.randomUUID(), UUID.randomUUID(), null, Set.of(), Set.of("tenant_admin"));

        String tz = resolver.resolve(scope);

        assertThat(tz).isEqualTo(TimezoneResolver.FALLBACK_TZ);
        assertThat(tz).isEqualTo("America/Mexico_City");
    }

    private static JdbcTemplate fakeReturning(String value) {
        return new JdbcTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return (T) value;
            }
        };
    }

    private static JdbcTemplate fakeThrowingEmpty() {
        return new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                throw new EmptyResultDataAccessException(1);
            }
        };
    }
}
