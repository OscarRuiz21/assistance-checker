package com.assistance.identity.internal.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Firma y valida JWT HS256 propios (solo perfiles local/demo — en prod los
 * tokens de usuario los firma Cognito y los valida el resource server contra
 * {@code AUTH_ISSUER_URI}, ver SecurityConfig). Access token vive 15 min;
 * refresh token vive 7 días y viaja en cookie httpOnly — nunca en JS.
 */
@Component
@Profile({"local", "demo"})
public class JwtIssuer {

    private static final String ISSUER = "assistance-checker-local";
    private static final long ACCESS_TTL_SECONDS = 15 * 60L;
    private static final long REFRESH_TTL_SECONDS = 7 * 24 * 3600L;

    private final byte[] secret;

    public JwtIssuer(@Value("${app.auth.jwt-secret}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "app.auth.jwt-secret debe tener al menos 32 bytes para HS256");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public record IssuedTokens(String accessToken, String refreshToken, long expiresInSeconds) {}

    public IssuedTokens issueTokens(UUID userId, UUID tenantId, String role, Set<UUID> plantIds) {
        Instant now = Instant.now();
        String access = sign(new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("type", "access")
                .claim("tenant_id", tenantId.toString())
                .claim("roles", List.of(role))
                .claim("plant_ids", plantIds.stream().map(UUID::toString).toList())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(ACCESS_TTL_SECONDS)))
                .build());

        String refresh = sign(new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("type", "refresh")
                .claim("tenant_id", tenantId.toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(REFRESH_TTL_SECONDS)))
                .build());

        return new IssuedTokens(access, refresh, ACCESS_TTL_SECONDS);
    }

    public record RefreshClaims(UUID userId, UUID tenantId) {}

    /** Valida un refresh token y devuelve (userId, tenantId) si es válido. */
    public Optional<RefreshClaims> verifyRefreshToken(String token) {
        return verify(token).filter(c -> "refresh".equals(stringClaim(c, "type")))
                .map(c -> new RefreshClaims(
                        UUID.fromString(c.getSubject()),
                        UUID.fromString(stringClaim(c, "tenant_id"))));
    }

    private Optional<JWTClaimsSet> verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(secret))) return Optional.empty();
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (ParseException | JOSEException e) {
            return Optional.empty();
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        try {
            return claims.getStringClaim(name);
        } catch (ParseException e) {
            return null;
        }
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("No se pudo firmar el JWT", e);
        }
    }
}
