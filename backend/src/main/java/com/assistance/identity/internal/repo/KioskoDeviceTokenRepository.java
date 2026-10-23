package com.assistance.identity.internal.repo;

import com.assistance.identity.internal.domain.KioskoDeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface KioskoDeviceTokenRepository extends JpaRepository<KioskoDeviceToken, UUID> {

    /**
     * Lookup puntual por hash (columna UNIQUE) — ver nota de RLS en
     * V12__identity_kiosko_device_token.sql: esta tabla no tiene RLS porque
     * el tenant todavía no se conoce en este punto del request.
     */
    Optional<KioskoDeviceToken> findByTokenHash(String tokenHash);
}
