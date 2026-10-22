package com.assistance.shared.internal.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA fallback: cuando el browser carga directo {@code /admin}, {@code /k} o
 * cualquier ruta de React Router (no es {@code /api/} ni {@code /actuator/}),
 * Spring sirve {@code /index.html} y el frontend toma control. Solo aplica en
 * producción cuando el bundle de Vite vive en {@code resources/static}; en dev
 * Vite tiene su propio fallback.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Rutas de primer nivel del SPA (no archivos estáticos con extensión).
        registry.addViewController("/k").setViewName("forward:/index.html");
        registry.addViewController("/k/{segment:[a-zA-Z0-9-_]+}").setViewName("forward:/index.html");
        registry.addViewController("/admin").setViewName("forward:/index.html");
        registry.addViewController("/admin/{segment:[a-zA-Z0-9-_]+}").setViewName("forward:/index.html");
    }
}
