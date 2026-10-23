package com.assistance.shared.internal.security;

import com.assistance.shared.api.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Corre después de que el resource server valida el JWT. Si hay una
 * {@link JwtAuthenticationToken} autenticada, puebla {@link TenantContext}
 * con el {@code TenantScope} del usuario para el resto del request y SIEMPRE
 * lo limpia en el {@code finally} (contrato de {@link TenantContext}).
 */
public class JwtTenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean populated = false;
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            try {
                TenantContext.set(JwtClaims.toTenantScope(jwtAuth.getToken()));
                populated = true;
            } catch (RuntimeException ignored) {
                // Claims mal formados: se deja sin tenant; el endpoint fallará
                // más adelante con 401/403 según corresponda, nunca con 500.
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (populated) {
                TenantContext.clear();
            }
        }
    }
}
