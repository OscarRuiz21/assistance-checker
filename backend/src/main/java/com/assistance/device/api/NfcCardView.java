package com.assistance.device.api;

import com.assistance.device.api.CardStatus;

import java.util.UUID;

public record NfcCardView(
        UUID id,
        UUID tenantId,
        String uid,
        UUID employeeId,
        CardStatus status,
        String blockedReason
) {}
