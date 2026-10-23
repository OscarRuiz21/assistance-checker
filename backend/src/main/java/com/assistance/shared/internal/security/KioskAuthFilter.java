package com.assistance.shared.internal.security;

import com.assistance.shared.api.KioskTokenService;
import com.assistance.shared.api.TenantContext;
import com.assistance.shared.api.TenantScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Autentica kioskos por token opaco: header {@code Authorization: Kiosk <token>}
 * (distinto del esquema {@code Bearer} usado por JWT de usuario, para que
 * ambos puedan coexistir en el mismo filtro chain sin ambigüedad). Si el
 * token es válido, puebla el SecurityContext con ROLE_kiosk y TenantContext
 * con el {@link TenantScope} del kiosko; limpia TenantContext SIEMPRE en el
 * finally.
 */
public class KioskAuthFilter extends OncePerRequestFilter {

    private static final String SCHEME = "Kiosk ";

    private final KioskTokenService kioskTokenService;

    public KioskAuthFilter(KioskTokenService kioskTokenService) {
        this.kioskTokenService = kioskTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        boolean populated = false;
        if (header != null && header.startsWith(SCHEME)) {
            String rawToken = header.substring(SCHEME.length()).trim();
            Optional<TenantScope> scope = kioskTokenService.authenticate(rawToken);
            if (scope.isPresent()) {
                TenantContext.set(scope.get());
                populated = true;
                SecurityContextHolder.getContext().setAuthentication(kioskAuthentication(scope.get()));
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

    private AbstractAuthenticationToken kioskAuthentication(TenantScope scope) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_kiosk"));
        var token = new AbstractAuthenticationToken(authorities) {
            @Override
            public Object getCredentials() { return ""; }

            @Override
            public Object getPrincipal() { return scope.kioskoId(); }
        };
        token.setAuthenticated(true);
        return token;
    }
}
