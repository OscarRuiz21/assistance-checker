package com.assistance.identity.internal.web;

import com.assistance.identity.internal.service.AuthServiceImpl;
import com.assistance.identity.internal.service.exception.InvalidRefreshTokenException;
import com.assistance.identity.internal.web.dto.AuthResponse;
import com.assistance.identity.internal.web.dto.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login/refresh/logout con password local. Solo existe en local/demo: en
 * prod la UI admin habla directo con Cognito (hosted UI u OIDC) y nunca
 * manda la password a este backend.
 */
@RestController
@RequestMapping("/api/auth")
@Profile({"local", "demo"})
public class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/auth";

    private final AuthServiceImpl authService;

    public AuthController(AuthServiceImpl authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        String lockoutKey = req.email().toLowerCase() + "|" + clientIp(request);
        var result = authService.login(req.email(), req.password(), lockoutKey);
        return withRefreshCookie(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null) {
            throw new InvalidRefreshTokenException();
        }
        var result = authService.refresh(refreshToken);
        return withRefreshCookie(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        ResponseCookie expired = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true).secure(true).sameSite("Strict").path(REFRESH_COOKIE_PATH).maxAge(0).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expired.toString()).build();
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(AuthServiceImpl.LoginResult result) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, result.tokens().refreshToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(java.time.Duration.ofDays(7))
                .build();

        var body = new AuthResponse(
                result.tokens().accessToken(),
                "Bearer",
                result.tokens().expiresInSeconds(),
                new AuthResponse.UserInfo(result.userId(), result.email(), result.role(), result.tenantId(), result.plantIds()));

        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(body);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
