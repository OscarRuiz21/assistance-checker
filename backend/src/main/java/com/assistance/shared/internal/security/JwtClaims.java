package com.assistance.shared.internal.security;

import com.assistance.shared.api.TenantScope;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lee claims de tenant/roles/plantas de un {@link Jwt}, soportando tanto los
 * tokens HS256 propios (local/demo: {@code tenant_id}, {@code roles},
 * {@code plant_ids}) como los de Cognito (prod: {@code custom:tenant_id},
 * {@code cognito:groups}; {@code plant_ids} lo agrega el Lambda pre-token
 * según doc 10, mismo nombre en ambos casos).
 */
final class JwtClaims {

    private JwtClaims() {
    }

    static List<String> roles(Jwt jwt) {
        List<String> cognito = jwt.getClaimAsStringList("cognito:groups");
        if (cognito != null && !cognito.isEmpty()) return cognito;
        List<String> local = jwt.getClaimAsStringList("roles");
        return local != null ? local : List.of();
    }

    static UUID tenantId(Jwt jwt) {
        String custom = jwt.getClaimAsString("custom:tenant_id");
        String tenantId = (custom != null && !custom.isBlank()) ? custom : jwt.getClaimAsString("tenant_id");
        if (tenantId == null) {
            throw new IllegalArgumentException("JWT sin claim de tenant_id");
        }
        return UUID.fromString(tenantId);
    }

    static Set<UUID> plantIds(Jwt jwt) {
        List<String> plantIds = jwt.getClaimAsStringList("plant_ids");
        if (plantIds == null) return Set.of();
        return plantIds.stream().map(UUID::fromString).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static TenantScope toTenantScope(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return new TenantScope(tenantId(jwt), userId, null, plantIds(jwt), Set.copyOf(roles(jwt)));
    }
}
