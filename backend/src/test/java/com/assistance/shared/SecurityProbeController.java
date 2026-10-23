package com.assistance.shared;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller mínimo usado solo por {@link SecurityConfigTest} para ejercer
 * las reglas de {@code authorizeHttpRequests}/{@code @PreAuthorize} de
 * {@code SecurityConfig} sin depender de los controllers reales (que
 * arrastran JPA y otros módulos).
 */
@RestController
class SecurityProbeController {

    @PostMapping("/api/events")
    Map<String, Boolean> createEvent() {
        return Map.of("ok", true);
    }

    @GetMapping("/api/employees/by-card/{uid}")
    Map<String, Boolean> byCard(@PathVariable String uid) {
        return Map.of("ok", true);
    }

    @GetMapping("/api/admin-only")
    @PreAuthorize("hasAnyRole('tenant_admin','rh')")
    Map<String, Boolean> adminOnly() {
        return Map.of("ok", true);
    }
}
