package com.assistance.shared;

import com.assistance.shared.api.KioskTokenService;
import com.assistance.shared.api.TenantScope;
import com.assistance.shared.internal.config.CorsConfig;
import com.assistance.shared.internal.config.SecurityConfig;
import com.assistance.shared.internal.security.TenantAwareJwtAuthenticationConverter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica el contrato de {@link SecurityConfig} sin levantar base de datos:
 * 401 sin credenciales, 403 por rol insuficiente, 200 con el rol correcto, y
 * el esquema propio {@code Authorization: Kiosk <token>} para rutas de
 * kiosko. Usa un controller de prueba ({@link SecurityProbeController}) en
 * vez de los controllers reales para no arrastrar sus dependencias (JPA, etc.).
 */
@WebMvcTest(controllers = SecurityProbeController.class)
@Import({SecurityConfig.class, CorsConfig.class, TenantAwareJwtAuthenticationConverter.class})
@EnableMethodSecurity
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KioskTokenService kioskTokenService;

    @Test
    void adminRouteWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/admin-only"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminRouteWithWrongRoleReturns403() throws Exception {
        mockMvc.perform(get("/api/admin-only").with(jwt().authorities(() -> "ROLE_supervisor")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRouteWithCorrectRoleReturns200() throws Exception {
        mockMvc.perform(get("/api/admin-only").with(jwt().authorities(() -> "ROLE_tenant_admin")))
                .andExpect(status().isOk());
    }

    @Test
    void kioskRouteWithoutKioskHeaderReturns401() throws Exception {
        // Sin Authorization header el principal es anónimo; Spring Security
        // trata "anónimo + rol insuficiente" como NO autenticado (401), no
        // como 403 (eso solo aplica a un principal ya autenticado de verdad
        // con el rol equivocado — ver adminRouteWithWrongRoleReturns403).
        mockMvc.perform(post("/api/events"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void kioskRouteWithInvalidKioskTokenReturns401() throws Exception {
        when(kioskTokenService.authenticate(eq("bad-token"))).thenReturn(Optional.empty());

        // KioskAuthFilter no autentica nada (token inválido) => igual que sin
        // header: anónimo => 401.
        mockMvc.perform(post("/api/events").header("Authorization", "Kiosk bad-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void kioskRouteWithValidKioskTokenReturns200() throws Exception {
        TenantScope scope = new TenantScope(
                UUID.randomUUID(), null, UUID.randomUUID(), Set.of(), Set.of("kiosk"));
        when(kioskTokenService.authenticate(eq("good-token"))).thenReturn(Optional.of(scope));

        mockMvc.perform(post("/api/events").header("Authorization", "Kiosk good-token"))
                .andExpect(status().isOk());
    }

    @Test
    void byCardLookupRequiresKioskAuth() throws Exception {
        mockMvc.perform(get("/api/employees/by-card/ABC123"))
                .andExpect(status().isUnauthorized());
    }
}
