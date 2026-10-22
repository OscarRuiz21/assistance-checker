package com.assistance.device.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record KioskoView(
        UUID id,
        UUID tenantId,
        UUID plantId,
        String name,
        String type,
        List<String> enabledMethods,
        String status,
        Instant lastHeartbeatAt
) {}
