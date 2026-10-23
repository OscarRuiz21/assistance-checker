package com.assistance.identity.internal.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record KioskPairRequest(@NotNull UUID kioskoId) {}
