package com.assistance.identity.internal.web.dto;

import java.util.Set;
import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserInfo user
) {
    public record UserInfo(UUID id, String email, String role, UUID tenantId, Set<UUID> plantIds) {}
}
