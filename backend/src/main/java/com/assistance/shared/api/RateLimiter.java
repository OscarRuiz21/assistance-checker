package com.assistance.shared.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Contador deslizante en memoria (Caffeine) para lockouts y rate limits
 * simples: PIN de empleado/kiosko, lookup by-card, login. Deliberadamente
 * básico — en multi-instancia (&gt;1 réplica del backend) esto NO comparte
 * estado entre instancias, así que un atacante distribuido entre réplicas
 * podría superar el límite nominal. Para producción multi-instancia mover
 * estos contadores a Redis o a una tabla en Postgres con
 * {@code INSERT ... ON CONFLICT} atómico. Documentado también en el reporte
 * de la Fase 1.
 */
public final class RateLimiter {

    private RateLimiter() {
    }

    public static Bucket windowOf(Duration window) {
        return new Bucket(window);
    }

    public static final class Bucket {
        private final Cache<String, AtomicInteger> cache;

        private Bucket(Duration window) {
            this.cache = Caffeine.newBuilder().expireAfterWrite(window).build();
        }

        /** Incrementa el contador de {@code key} y devuelve el nuevo valor. */
        public int increment(String key) {
            return cache.asMap()
                    .computeIfAbsent(key, k -> new AtomicInteger())
                    .incrementAndGet();
        }

        public int current(String key) {
            AtomicInteger v = cache.getIfPresent(key);
            return v == null ? 0 : v.get();
        }

        public void reset(String key) {
            cache.invalidate(key);
        }
    }
}
