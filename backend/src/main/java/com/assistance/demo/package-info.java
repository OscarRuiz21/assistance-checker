/**
 * Módulo Demo. Endpoints de seed/reset y listener cross-módulo de ejemplo.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Demo",
        allowedDependencies = {"attendance::api", "attendance::event"}
)
package com.assistance.demo;
