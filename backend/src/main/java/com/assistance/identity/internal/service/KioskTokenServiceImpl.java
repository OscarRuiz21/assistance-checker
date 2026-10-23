package com.assistance.identity.internal.service;

import com.assistance.device.api.DeviceApi;
import com.assistance.shared.api.KioskTokenService;
import com.assistance.identity.internal.domain.KioskoDeviceToken;
import com.assistance.identity.internal.repo.KioskoDeviceTokenRepository;
import com.assistance.shared.api.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

@Service
public class KioskTokenServiceImpl implements KioskTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KioskoDeviceTokenRepository tokens;
    private final DeviceApi devices;

    public KioskTokenServiceImpl(KioskoDeviceTokenRepository tokens, DeviceApi devices) {
        this.tokens = tokens;
        this.devices = devices;
    }

    /** Genera un token opaco de 32 bytes aleatorios, codificado base64url. */
    public static String generateRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }

    @Override
    @Transactional
    public Optional<TenantScope> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        String hash = sha256Hex(rawToken);
        KioskoDeviceToken token = tokens.findByTokenHash(hash).orElse(null);
        if (token == null || !token.isActive()) return Optional.empty();

        token.setLastSeenAt(Instant.now());
        tokens.save(token);

        var kiosko = devices.findKiosko(token.getKioskoId()).orElse(null);
        Set<java.util.UUID> plantIds = kiosko != null ? Set.of(kiosko.plantId()) : Set.of();

        return Optional.of(new TenantScope(
                token.getTenantId(), null, token.getKioskoId(), plantIds, Set.of("kiosk")));
    }
}
