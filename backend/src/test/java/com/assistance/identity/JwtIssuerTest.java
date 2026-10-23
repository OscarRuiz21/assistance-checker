package com.assistance.identity;

import com.assistance.identity.internal.service.JwtIssuer;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtIssuer} firma/valida HS256 propio (solo local/demo). Sin Spring
 * context: se instancia directo con un secreto de prueba de 32+ bytes.
 */
class JwtIssuerTest {

    private static final String SECRET = "test-only-hmac-secret-at-least-32-bytes-long!!";

    @Test
    void rejectsSecretsShorterThan32Bytes() {
        assertThatThrownBy(() -> new JwtIssuer("too-short"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void issuedRefreshTokenCanBeVerifiedBackToSameUserAndTenant() {
        JwtIssuer issuer = new JwtIssuer(SECRET);
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        var tokens = issuer.issueTokens(userId, tenantId, "tenant_admin", Set.of());

        var claims = issuer.verifyRefreshToken(tokens.refreshToken()).orElseThrow();
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.tenantId()).isEqualTo(tenantId);
    }

    @Test
    void accessTokenIsNotAValidRefreshToken() {
        JwtIssuer issuer = new JwtIssuer(SECRET);
        var tokens = issuer.issueTokens(UUID.randomUUID(), UUID.randomUUID(), "rh", Set.of());

        assertThat(issuer.verifyRefreshToken(tokens.accessToken())).isEmpty();
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() {
        JwtIssuer issuerA = new JwtIssuer(SECRET);
        JwtIssuer issuerB = new JwtIssuer("another-test-only-hmac-secret-32-bytes-min!");
        var tokens = issuerA.issueTokens(UUID.randomUUID(), UUID.randomUUID(), "rh", Set.of());

        assertThat(issuerB.verifyRefreshToken(tokens.refreshToken())).isEmpty();
    }

    @Test
    void garbageTokenIsRejectedWithoutThrowing() {
        JwtIssuer issuer = new JwtIssuer(SECRET);
        assertThat(issuer.verifyRefreshToken("not-a-jwt-at-all")).isEmpty();
    }
}
