/**
 * Módulo Device. Encapsula tarjetas NFC, kioskos y subtipos de dispositivo.
 *
 * NamedInterfaces públicos:
 *   device::api    — port (DeviceApi) + view (NfcCardView, KioskoView) + value (CardStatus)
 *
 * Otros módulos solo declaran {@code "device::api"} en allowedDependencies;
 * no necesitan listar view/value por separado.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Device",
        allowedDependencies = {"shared::api"}
)
package com.assistance.device;
