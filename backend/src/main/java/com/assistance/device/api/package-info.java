/**
 * NamedInterface "api" del módulo Device. Contiene todo el contrato síncrono:
 *   - DeviceApi      (port — la interfaz que llaman otros módulos)
 *   - NfcCardView    (DTO de salida)
 *   - KioskoView     (DTO de salida)
 *   - CardStatus     (value object / enum embebido en views)
 *
 * Convención de nombres: {@code *Api} = port, {@code *View} = read model,
 * {@code *Status / *Type / *Method} = value object.
 */
@org.springframework.modulith.NamedInterface("api")
package com.assistance.device.api;
