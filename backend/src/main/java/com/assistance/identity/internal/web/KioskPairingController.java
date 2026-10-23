package com.assistance.identity.internal.web;

import com.assistance.device.api.DeviceApi;
import com.assistance.identity.internal.domain.KioskoDeviceToken;
import com.assistance.identity.internal.repo.KioskoDeviceTokenRepository;
import com.assistance.identity.internal.service.KioskTokenServiceImpl;
import com.assistance.identity.internal.service.exception.KioskPairingDeniedException;
import com.assistance.identity.internal.web.dto.KioskPairRequest;
import com.assistance.identity.internal.web.dto.KioskPairResponse;
import com.assistance.shared.api.TenantContext;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Pairing de kiosko: disponible en TODOS los perfiles (incluido prod), a
 * diferencia de {@link AuthController}. Requiere JWT de tenant_admin —
 * funciona igual con Cognito (prod) o con JWT local HS256 (local/demo)
 * porque solo depende de la autenticación/roles resueltos por SecurityConfig.
 */
@RestController
@RequestMapping("/api/auth/kiosk")
public class KioskPairingController {

    private final DeviceApi devices;
    private final KioskoDeviceTokenRepository tokens;

    public KioskPairingController(DeviceApi devices, KioskoDeviceTokenRepository tokens) {
        this.devices = devices;
        this.tokens = tokens;
    }

    @PostMapping("/pair")
    @PreAuthorize("hasRole('tenant_admin')")
    public KioskPairResponse pair(@Valid @RequestBody KioskPairRequest req) {
        var scope = TenantContext.require();
        var kiosko = devices.findKiosko(req.kioskoId())
                .orElseThrow(() -> new KioskPairingDeniedException("Kiosko no encontrado"));

        if (!kiosko.tenantId().equals(scope.tenantId())) {
            // No revelamos si el kiosko existe en otro tenant.
            throw new KioskPairingDeniedException("Kiosko no encontrado");
        }

        String rawToken = KioskTokenServiceImpl.generateRawToken();
        KioskoDeviceToken entity = new KioskoDeviceToken(
                UUID.randomUUID(),
                scope.tenantId(),
                req.kioskoId(),
                KioskTokenServiceImpl.sha256Hex(rawToken),
                Instant.now());
        tokens.save(entity);

        return new KioskPairResponse(rawToken, req.kioskoId(), scope.tenantId());
    }
}
