/**
 * Módulo Identity. Login de usuarios admin (local/demo con password bcrypt;
 * prod delega en Cognito), pairing y autenticación de kioskos por token
 * opaco.
 *
 * No expone NamedInterfaces propios: implementa
 * {@code shared.api.KioskTokenService} (puerto definido en el kernel, no
 * aquí) para que {@code shared.internal.security.KioskAuthFilter} pueda
 * consumirlo sin que {@code shared} dependa de {@code identity} — evita el
 * ciclo {@code shared -> identity -> device -> shared} (identity depende de
 * {@code device::api}). Ver el javadoc de {@code shared.api.KioskTokenService}
 * para el detalle.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Identity",
        allowedDependencies = {"shared::api", "device::api"}
)
package com.assistance.identity;
