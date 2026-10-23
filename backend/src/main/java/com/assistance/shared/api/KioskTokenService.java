package com.assistance.shared.api;

import java.util.Optional;

/**
 * Puerto que resuelve un token opaco de kiosko (header
 * {@code Authorization: Kiosk <token>}) en un {@link TenantScope}. Vive en el
 * módulo {@code shared} (el kernel) — no en {@code identity}, que es quien lo
 * implementa ({@code identity.internal.service.KioskTokenServiceImpl}) —
 * precisamente para que {@code shared.internal.security.KioskAuthFilter} (el
 * filtro de seguridad central) pueda consumirlo sin que {@code shared}
 * dependa de {@code identity}. Si el puerto viviera en {@code identity::api},
 * se formaría un ciclo de módulos: {@code shared -> identity -> device ->
 * shared} (identity depende de {@code device::api} para resolver kioskos),
 * que Spring Modulith rechaza en {@code ModularityTests}. Con el puerto aquí,
 * la dependencia es {@code identity -> shared} (ya permitida) y
 * {@code shared} no depende de nadie — patrón de puertos y adaptadores
 * estándar, no un caso especial de este proyecto.
 */
public interface KioskTokenService {

    /**
     * Valida el token en claro (se hashea internamente con sha256 antes de
     * consultar). Si es válido y no revocado, actualiza {@code last_seen_at}
     * y retorna el scope del kiosko. No lanza excepción si el token no
     * existe o fue revocado — el filtro decide cómo responder (401).
     */
    Optional<TenantScope> authenticate(String rawToken);
}
