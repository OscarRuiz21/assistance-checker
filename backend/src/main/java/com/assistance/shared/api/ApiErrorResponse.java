package com.assistance.shared.api;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
        String code,
        String message,
        Instant timestamp,
        List<FieldError> errors
) {
    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(code, message, Instant.now(), null);
    }

    public static ApiErrorResponse validation(List<FieldError> errors) {
        return new ApiErrorResponse("VALIDATION_FAILED", "Errores de validación",
                Instant.now(), errors);
    }

    public record FieldError(String field, String message) {}
}
