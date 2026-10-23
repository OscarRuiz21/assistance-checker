/**
 * Módulo Attendance — núcleo del producto.
 *
 * NamedInterfaces públicos:
 *   attendance::api    — port + command + view + value (sync API surface)
 *   attendance::event  — domain events (async surface, AttendanceRecorded)
 *
 * Otros módulos solo declaran {@code "attendance::api"} para llamadas síncronas
 * y/o {@code "attendance::event"} para escuchar eventos. Sin entradas separadas
 * para view/value/command.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Attendance",
        allowedDependencies = {"shared::api", "device::api", "employee::api", "compliance::api"}
)
package com.assistance.attendance;
