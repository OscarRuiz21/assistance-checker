/**
 * Tipos públicos del módulo Shared (kernel transversal). Otros módulos
 * importan {@link ApiException} para definir sus propias excepciones tipadas
 * y {@link ApiErrorResponse} solo si necesitan formatear errores manualmente
 * (normalmente lo hace GlobalExceptionHandler).
 */
@org.springframework.modulith.NamedInterface("api")
package com.assistance.shared.api;
