/**
 * Módulo compartido (kernel). Provee tipos transversales: excepciones HTTP,
 * handler global y configuración CORS. Cualquier módulo puede importarlo.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Shared"
)
package com.assistance.shared;
