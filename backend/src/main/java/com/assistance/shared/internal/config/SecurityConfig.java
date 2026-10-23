package com.assistance.shared.internal.config;

import com.assistance.shared.api.KioskTokenService;
import com.assistance.shared.api.ApiErrorResponse;
import com.assistance.shared.internal.security.JwtTenantContextFilter;
import com.assistance.shared.internal.security.KioskAuthFilter;
import com.assistance.shared.internal.security.TenantAwareJwtAuthenticationConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Security central: stateless, resource server JWT (Cognito en prod, HS256
 * propio en local/demo), autenticación de kioskos por token opaco, RBAC vía
 * {@code @PreAuthorize} en cada controller, headers de seguridad y CORS por
 * lista explícita. Puebla {@code TenantContext} (ver
 * {@link JwtTenantContextFilter} y {@link KioskAuthFilter}) — nunca lo deja
 * sin limpiar entre requests.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            TenantAwareJwtAuthenticationConverter jwtAuthenticationConverter,
            JwtDecoder jwtDecoder,
            KioskTokenService kioskTokenService,
            CorsConfigurationSource corsConfigurationSource,
            ObjectMapper objectMapper) throws Exception {

        http
                .csrf(csrf -> csrf.disable()) // API stateless con Bearer/token opaco, no cookies de sesión
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authz -> authz
                        // Auth público (login/refresh/logout solo existen como beans en
                        // local/demo; en prod estas rutas simplemente no resuelven a
                        // ningún controller y Spring devuelve 404, no 401/403).
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
                        .permitAll()
                        // Captura de eventos y lookup de tarjeta: autenticación de kiosko
                        // (header Authorization: Kiosk <token>), nunca JWT de usuario.
                        .requestMatchers(HttpMethod.POST, "/api/events").hasRole("kiosk")
                        .requestMatchers(HttpMethod.GET, "/api/employees/by-card/**").hasRole("kiosk")
                        // Resto de /api/**: JWT de usuario admin; el rol exacto se valida
                        // por @PreAuthorize en cada controller.
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/actuator/health").permitAll()
                        // Todo lo demás es el SPA (React Router) servido por WebMvcConfig /
                        // assets estáticos del build de Vite.
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(jsonAuthEntryPoint(objectMapper)))
                .exceptionHandling(ex -> ex
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    ApiErrorResponse.of("ACCESS_DENIED", "No tiene permisos para esta operación")));
                        }))
                .addFilterBefore(new KioskAuthFilter(kioskTokenService), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new JwtTenantContextFilter(), BearerTokenAuthenticationFilter.class)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                                        + "img-src 'self' data:; font-src 'self' data: https://rsms.me; "
                                        + "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; "
                                        + "form-action 'self'"))
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        // HSTS: Spring Security solo lo escribe si request.isSecure() (HTTPS),
                        // cumpliendo "solo si https" sin configuración adicional.
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)));

        return http.build();
    }

    private org.springframework.security.web.AuthenticationEntryPoint jsonAuthEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                    new ApiErrorResponse("UNAUTHENTICATED", "Autenticación requerida o inválida", Instant.now(), null)));
        };
    }

    /** Prod: Cognito. Resuelve metadata OIDC desde AUTH_ISSUER_URI. */
    @Bean
    @Profile("prod")
    public JwtDecoder prodJwtDecoder(@Value("${app.auth.issuer-uri}") String issuerUri) {
        return JwtDecoders.fromIssuerLocation(issuerUri);
    }

    /**
     * No-prod: HS256 con el secreto propio. Si el secreto está vacío (por
     * ejemplo en un test sin perfil activo), se usa un secreto efímero solo
     * para poder construir el bean — nunca se usa para firmar tokens reales
     * (eso lo hace {@code JwtIssuer}, que sí falla rápido si el secreto es
     * inválido).
     */
    @Bean
    @Profile("!prod")
    public JwtDecoder localJwtDecoder(@Value("${app.auth.jwt-secret:}") String secret) {
        String effective = (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
                ? "fallback-only-for-context-bootstrap-not-a-real-secret!!"
                : secret;
        SecretKeySpec key = new SecretKeySpec(effective.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
