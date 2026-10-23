package com.assistance.identity.internal.service;

import com.assistance.identity.internal.domain.AppUser;
import com.assistance.identity.internal.repo.AppUserRepository;
import com.assistance.identity.internal.repo.UserPlantAccessRepository;
import com.assistance.identity.internal.service.exception.InvalidCredentialsException;
import com.assistance.identity.internal.service.exception.InvalidRefreshTokenException;
import com.assistance.identity.internal.service.exception.LoginLockedException;
import com.assistance.shared.api.RateLimiter;
import com.assistance.shared.api.TenantContext;
import com.assistance.shared.api.TenantScope;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Login/refresh con password local (bcrypt) — solo perfiles local/demo. En
 * prod los usuarios se autentican directo contra Cognito (hosted UI / SDK del
 * cliente) y el backend solo valida el JWT resultante; este servicio no se
 * usa en ese camino.
 */
@Service
@Profile({"local", "demo"})
public class AuthServiceImpl {

    private static final int MAX_FAILS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private final RateLimiter.Bucket loginFails = RateLimiter.windowOf(WINDOW);

    private final AppUserRepository appUsers;
    private final UserPlantAccessRepository plantAccess;
    private final JwtIssuer jwtIssuer;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthServiceImpl(AppUserRepository appUsers, UserPlantAccessRepository plantAccess, JwtIssuer jwtIssuer) {
        this.appUsers = appUsers;
        this.plantAccess = plantAccess;
        this.jwtIssuer = jwtIssuer;
    }

    public record LoginResult(JwtIssuer.IssuedTokens tokens, UUID userId, UUID tenantId,
                               String email, String role, Set<UUID> plantIds) {}

    @Transactional(readOnly = true)
    public LoginResult login(String email, String password, String lockoutKey) {
        if (loginFails.current(lockoutKey) >= MAX_FAILS) {
            throw new LoginLockedException();
        }

        AppUser user = appUsers.findActiveByEmailForLogin(email).orElse(null);
        boolean ok = user != null
                && user.getPasswordHash() != null
                && passwordEncoder.matches(password, user.getPasswordHash());

        if (!ok) {
            loginFails.increment(lockoutKey);
            throw new InvalidCredentialsException();
        }
        loginFails.reset(lockoutKey);

        Set<UUID> plantIds = plantAccess.findAllByUserId(user.getId()).stream()
                .map(com.assistance.identity.internal.domain.UserPlantAccess::getPlantId)
                .collect(Collectors.toUnmodifiableSet());

        var tokens = jwtIssuer.issueTokens(user.getId(), user.getTenantId(), user.getRole(), plantIds);
        return new LoginResult(tokens, user.getId(), user.getTenantId(), user.getEmail(), user.getRole(), plantIds);
    }

    @Transactional(readOnly = true)
    public LoginResult refresh(String refreshToken) {
        var claims = jwtIssuer.verifyRefreshToken(refreshToken).orElseThrow(InvalidRefreshTokenException::new);

        // El tenant ya viaja firmado dentro del propio refresh token (claim
        // tenant_id), así que no hace falta adivinarlo: seteamos TenantContext
        // con el tenant real antes de leer app_user por id, para que RLS (una
        // vez que el Agente B conecte TenantContext -> `SET app.tenant_id`)
        // deje pasar esta lectura puntual por PK.
        TenantScope readScope = new TenantScope(claims.tenantId(), claims.userId(), null, Set.of(), Set.of("system"));
        AppUser user = TenantContext.runAs(readScope, () -> appUsers.findById(claims.userId()).orElse(null));

        if (user == null || !user.isActive()) {
            throw new InvalidRefreshTokenException();
        }

        Set<UUID> plantIds = plantAccess.findAllByUserId(user.getId()).stream()
                .map(com.assistance.identity.internal.domain.UserPlantAccess::getPlantId)
                .collect(Collectors.toUnmodifiableSet());

        var tokens = jwtIssuer.issueTokens(user.getId(), user.getTenantId(), user.getRole(), plantIds);
        return new LoginResult(tokens, user.getId(), user.getTenantId(), user.getEmail(), user.getRole(), plantIds);
    }
}
