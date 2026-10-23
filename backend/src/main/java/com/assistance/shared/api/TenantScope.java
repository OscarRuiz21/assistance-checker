package com.assistance.shared.api;

import java.util.Set;
import java.util.UUID;

/**
 * Identidad resuelta de la request actual. {@code userId} es null para kioskos,
 * {@code kioskoId} es null para usuarios admin. {@code plantIds} vacío significa
 * "todas las plantas del tenant" (solo para tenant_admin / super_admin).
 */
public record TenantScope(
        UUID tenantId,
        UUID userId,
        UUID kioskoId,
        Set<UUID> plantIds,
        Set<String> roles
) {
    public TenantScope {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        plantIds = plantIds == null ? Set.of() : Set.copyOf(plantIds);
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static TenantScope system(UUID tenantId) {
        return new TenantScope(tenantId, null, null, Set.of(), Set.of("system"));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean canAccessPlant(UUID plantId) {
        return plantIds.isEmpty() || plantIds.contains(plantId);
    }
}
