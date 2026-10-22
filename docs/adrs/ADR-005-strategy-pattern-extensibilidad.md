# ADR-005: Strategy pattern para puntos de extensibilidad

**Estado**: Aceptado
**Fecha**: 2026-04-27
**Decisores**: Tech Lead

## Contexto

El producto tiene varias capacidades que evolucionarán en v1.1 / v2 sin modificar el core. Ejemplos:

- **Exporters de nómina**: MVP entrega CSV genérico. v2 agrega CONTPAQi, NOI, SAP HR.
- **Métodos de captura**: MVP soporta NFC + PIN. v1.1 agrega huella biométrica. v2 agrega face.
- **Enrichers de eventos**: MVP no captura foto. v2 sí.
- **Webhooks**: MVP no expone webhooks. v2 sí.
- **Notificaciones**: MVP solo email. v2 agrega SMS, WhatsApp, push.

Si en MVP cableamos cada feature directamente al core, agregar v1.1/v2 implica modificar código existente, romper tests, abrir riesgos en módulos estables.

## Decisión

**Toda capacidad con variantes futuras vive detrás de una interfaz Spring desde el MVP**, con implementación default tipo `NoOp` o `Generic`. Las variantes futuras se agregan como nuevos `@Component` que implementan la misma interfaz, sin tocar el core.

Patrón: **Strategy + plugin discovery via Spring `Map<String, Strategy>` injection**.

## Razonamiento

### El principio: Open/Closed

El sistema está **abierto a extensión**, **cerrado a modificación**. Agregar una nueva nómina en v2 = nueva clase + cero cambios en código existente.

### Cómo se implementa

```java
// 1. Define interfaz en MVP
public interface PayrollExporter {
    String getFormatId();                       // "csv-generic", "contpaqi", "noi"
    boolean supports(Tenant tenant);            // ¿este tenant requiere este formato?
    ExportResult export(PayrollPeriod period); 
}

// 2. Implementación default en MVP
@Component
public class CsvGenericExporter implements PayrollExporter {
    public String getFormatId() { return "csv-generic"; }
    public boolean supports(Tenant tenant) { return true; }  // fallback
    public ExportResult export(PayrollPeriod period) { ... }
}

// 3. v2: agregar nuevas implementaciones SIN modificar nada existente
@Component
public class ContpaqiExporter implements PayrollExporter {
    public String getFormatId() { return "contpaqi"; }
    public boolean supports(Tenant tenant) { 
        return "contpaqi".equals(tenant.getExporterFormat()); 
    }
    public ExportResult export(PayrollPeriod period) { ... }
}

// 4. Resolver desde el servicio
@Service
public class PayrollExportService {
    private final List<PayrollExporter> exporters;  // Spring inyecta TODOS los implementadores
    
    public ExportResult export(PayrollPeriod period) {
        Tenant tenant = period.getTenant();
        return exporters.stream()
            .filter(e -> e.supports(tenant))
            .findFirst()
            .orElse(getDefault())  // CsvGenericExporter como fallback
            .export(period);
    }
}
```

### Puntos de extensibilidad identificados

```java
// 1. Exporters de nómina
public interface PayrollExporter { ... }
  → MVP: CsvGenericExporter
  → v2: ContpaqiExporter, NoiExporter, SapHrExporter

// 2. Métodos de captura
public interface CaptureMethodValidator { ... }
  → MVP: NfcValidator, PinValidator
  → v1.1: FingerprintValidator, FaceValidator

// 3. Enrichers de eventos (ej. agregar foto al recibir)
public interface EventEnricher { ... }
  → MVP: NoOpEnricher
  → v2: PhotoEnricher

// 4. Publishers de webhooks salientes
public interface WebhookPublisher { ... }
  → MVP: NoOpPublisher
  → v2: HttpWebhookPublisher

// 5. Canales de notificación
public interface NotificationChannel { ... }
  → MVP: EmailChannel (SES)
  → v1.1: PushNotificationChannel (FCM/APNS)
  → v2: SmsChannel (Twilio), WhatsAppChannel

// 6. Detectores del review queue
public interface ReviewQueueDetector { ... }
  → MVP: MultiKioskoConflictDetector, MissingCheckDetector,
         ExtendedShiftDetector, OutOfWindowDetector
  → v1.1+: detectores nuevos según feedback de cliente

// 7. Importers de empleados (formatos de nómina externa)
public interface EmployeeImporter { ... }
  → MVP: CsvGenericImporter
  → v1.1+: ContpaqiImporter, NoiImporter
```

### Por qué no usamos plugins externos (JAR loading dinámico)

Spring class scanning en startup es suficiente. Plugin loading dinámico (drop a JAR) introduce:
- Riesgo de seguridad (código no auditado).
- Complejidad de classpath isolation.
- Overhead operacional.

Para nuestro modelo (extensiones desarrolladas internamente, deploys planificados), Spring component scan + redeploy es suficiente.

### Por qué no usamos service registry / OSGi

Overkill para 5-10 puntos de extensibilidad. Spring dependency injection cubre 100% del caso.

## Consecuencias

### Positivas

- **Extensibilidad sin modificación**: agregar v2 = nueva clase, sin tocar core.
- **Testabilidad**: cada implementación testeable en aislamiento.
- **Polimorfismo natural**: el código que llama no sabe ni le importa qué exporter está usando.
- **Documentación implícita**: la lista de interfaces es la lista de "puntos de evolución del producto".

### Negativas

- **Sobrecabeza inicial**: MVP escribe interfaces aunque solo tenga 1 implementación. ~1-2 horas extra por punto, pagado con creces en v1.1.
- **Resolución cuesta una iteración**: el `for` sobre la lista de strategies es O(N), pero N es pequeño (≤10).

### Configuración por tenant

Los puntos de extensibilidad se eligen por tenant en `tenant.config` o `plant.policy_override`:

```json
{
  "exporter_format": "csv-generic",        // o "contpaqi", "noi" en v2
  "notification_channels": ["email"],      // o ["email", "push"] en v1.1
  "enabled_capture_methods": ["nfc", "pin"], // o ["nfc","pin","fingerprint"] en v1.1
  "enabled_review_detectors": ["all"]      // o lista específica para customizar
}
```

El servicio resuelve la implementación correcta en runtime sin if/else hardcoded.

## Anti-patrones evitados

```java
// ❌ Mal: switch/case sobre formatos
public ExportResult export(Tenant tenant, PayrollPeriod period) {
    switch (tenant.getExporterFormat()) {
        case "csv-generic": return csvExport(period);
        case "contpaqi": return contpaqiExport(period);  // agregado en v2 = modificar este método
        ...
    }
}

// ❌ Mal: factory hardcoded
public ExportResult export(Tenant tenant, PayrollPeriod period) {
    PayrollExporter exporter;
    if ("contpaqi".equals(tenant.getExporterFormat())) {
        exporter = new ContpaqiExporter();
    } else {
        exporter = new CsvGenericExporter();
    }
    return exporter.export(period);
}

// ✅ Bien: Strategy + Spring inyección
public ExportResult export(PayrollPeriod period) {
    return exporters.stream()
        .filter(e -> e.supports(period.getTenant()))
        .findFirst()
        .orElseThrow()
        .export(period);
}
```

## Alternativas rechazadas

- **Switch/case con feature flags**: no escala, viola Open/Closed.
- **OSGi / plugin runtime loading**: overkill para 5-10 puntos de extensibilidad.
- **Microservicios separados por feature**: prematuro y operacionalmente caro para equipo de 3.

## Métricas de éxito

- Agregar `ContpaqiExporter` en v2 toma ≤ 3 días incluyendo tests, sin tocar archivo existente del core.
- 0 referencias a feature flags hardcoded en código de servicios.
- Tests de cada strategy son aislados (mock del request/response, sin dependencias del core).

## Re-evaluar cuando

- Llegamos a > 30 puntos de extensibilidad: considerar plugin framework explícito.
- Strategies de terceros (clientes que escriban su propio exporter): agregar OSGi o classpath isolation.
- Performance del `for` sobre N strategies se vuelve issue (improbable hasta N > 100).
