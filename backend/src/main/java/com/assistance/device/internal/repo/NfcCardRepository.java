package com.assistance.device.internal.repo;

import com.assistance.device.internal.domain.NfcCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NfcCardRepository extends JpaRepository<NfcCard, UUID> {

    /**
     * Filtro explícito por tenant además de RLS (defensa en profundidad,
     * Fase 2.3): dos tenants podrían tener tarjetas con el mismo UID físico
     * (UNIQUE es por (tenant_id, uid), no global), así que buscar solo por
     * UID sin tenant sería ambiguo/incorrecto incluso si RLS lo cubriera.
     */
    Optional<NfcCard> findByTenantIdAndUid(UUID tenantId, String uid);
}
