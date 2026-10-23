package com.assistance.identity.internal.web.dto;

import java.util.UUID;

public record KioskPairResponse(String token, UUID kioskoId, UUID tenantId) {}
