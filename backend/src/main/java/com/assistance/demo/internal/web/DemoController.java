package com.assistance.demo.internal.web;

import com.assistance.demo.internal.service.DemoSeedService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoints destructivos de demo (reset borra eventos). Solo existen en los
 * perfiles local/demo; en prod el bean ni siquiera se registra, así que las
 * rutas devuelven 404 (no 403 — no hay pista de que exista el endpoint).
 * Restringido además a tenant_admin por si el perfil demo se expone a varios
 * usuarios con roles distintos.
 */
@RestController
@RequestMapping("/api/demo")
@Profile({"local", "demo"})
public class DemoController {

    private final DemoSeedService seed;

    public DemoController(DemoSeedService seed) {
        this.seed = seed;
    }

    @PostMapping("/seed")
    @PreAuthorize("hasRole('tenant_admin')")
    public Map<String, Object> seed() {
        return Map.of("ok", true, "eventsGenerated", seed.seed());
    }

    @PostMapping("/reset")
    @PreAuthorize("hasRole('tenant_admin')")
    public Map<String, Object> reset() {
        return Map.of("ok", true, "eventsDeleted", seed.reset());
    }
}
