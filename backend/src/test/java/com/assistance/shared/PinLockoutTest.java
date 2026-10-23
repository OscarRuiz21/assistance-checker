package com.assistance.shared;

import com.assistance.employee.internal.service.PinVerifier;
import com.assistance.shared.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lockout de PIN por empleado (docs/16-readiness-plan.md Fase 1.5): 5 fallos
 * en 15 min bloquean con 429 PIN_LOCKED sin volver a tocar la base de datos.
 * No necesita Spring context ni Postgres: {@link PinVerifier} solo depende de
 * {@link JdbcTemplate}, que aquí se mockea.
 */
class PinLockoutTest {

    @Test
    void locksAfterFiveFailuresAndStopsHittingTheDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any())).thenReturn(false);
        PinVerifier verifier = new PinVerifier(jdbc);
        UUID employeeId = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            assertThat(verifier.verify(employeeId, "0000")).isFalse();
        }
        verify(jdbc, org.mockito.Mockito.times(5))
                .queryForObject(anyString(), eq(Boolean.class), any(), any());

        assertThatThrownBy(() -> verifier.verify(employeeId, "0000"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(api.code()).isEqualTo("PIN_LOCKED");
                });

        // El 6to intento no debe volver a consultar la DB: el lockout corta antes.
        verify(jdbc, org.mockito.Mockito.times(5))
                .queryForObject(anyString(), eq(Boolean.class), any(), any());
    }

    @Test
    void successfulVerificationResetsTheCounter() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any()))
                .thenReturn(false, false, false, false, true, false, false, false, false, false);
        PinVerifier verifier = new PinVerifier(jdbc);
        UUID employeeId = UUID.randomUUID();

        assertThat(verifier.verify(employeeId, "0000")).isFalse();
        assertThat(verifier.verify(employeeId, "0000")).isFalse();
        assertThat(verifier.verify(employeeId, "0000")).isFalse();
        assertThat(verifier.verify(employeeId, "0000")).isFalse();
        assertThat(verifier.verify(employeeId, "1234")).isTrue(); // resetea el contador

        // Como el contador se reseteó, deben caber 4 fallos más sin bloquear.
        for (int i = 0; i < 4; i++) {
            assertThat(verifier.verify(employeeId, "0000")).isFalse();
        }
    }

    @Test
    void blankPinIsRejectedWithoutTouchingTheDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PinVerifier verifier = new PinVerifier(jdbc);

        assertThat(verifier.verify(UUID.randomUUID(), "")).isFalse();
        assertThat(verifier.verify(UUID.randomUUID(), null)).isFalse();

        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), any(), any());
    }
}
