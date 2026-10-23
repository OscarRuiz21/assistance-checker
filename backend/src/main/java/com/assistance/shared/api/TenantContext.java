package com.assistance.shared.api;

import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;

/**
 * Tenant de la request/hilo actual. Lo puebla el filtro de seguridad (JWT de
 * usuario o token de kiosko) y lo limpia al terminar la request. Jobs y seeds
 * deben usar {@link #runAs(TenantScope, Supplier)}.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantScope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantScope scope) {
        CURRENT.set(scope);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<TenantScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static TenantScope require() {
        TenantScope scope = CURRENT.get();
        if (scope == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TENANT_REQUIRED", "No tenant in context");
        }
        return scope;
    }

    public static <T> T runAs(TenantScope scope, Supplier<T> action) {
        TenantScope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
