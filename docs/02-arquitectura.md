# Arquitectura técnica

## Vista de alto nivel

```
┌──────────────────────────────────────────────────────────────────────┐
│  CLIENTES                                                             │
│                                                                       │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────────┐  │
│  │ ZKTeco K50   │   │ Web Admin    │   │ Web Kiosko Mode          │  │
│  │ Pro (planta) │   │ + Supervisor │   │ (fallback / contingencia)│  │
│  │ ADMS Push    │   │ React SPA    │   │ React, mismo SPA         │  │
│  └──────┬───────┘   └──────┬───────┘   └──────────┬───────────────┘  │
└─────────┼──────────────────┼──────────────────────┼──────────────────┘
          │ HTTPS push       │ HTTPS user JWT       │ HTTPS device JWT
          ▼                  ▼                      ▼
┌──────────────────────────────────────────────────────────────────────┐
│  AWS                                                                  │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │ CloudFront (CDN para admin web)                                 │  │
│  └────────────────────────────────┬───────────────────────────────┘  │
│                                   │                                   │
│  ┌────────────────────────────────▼───────────────────────────────┐  │
│  │ Application Load Balancer (ALB)                                 │  │
│  │   • TLS termination                                             │  │
│  │   • Path routing: /api/v1/* vs /iclock/* vs static              │  │
│  │   • Health checks                                               │  │
│  └────────────────────────────────┬───────────────────────────────┘  │
│                                   │                                   │
│  ┌────────────────────────────────▼───────────────────────────────┐  │
│  │ Spring Boot Monolith en ECS Fargate                             │  │
│  │                                                                 │  │
│  │ ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐ │  │
│  │ │ /api/v1/*        │ │ /iclock/*        │ │ Jobs schedulers  │ │  │
│  │ │ REST API         │ │ Push Adapter     │ │ • Cálculo nocturno│ │  │
│  │ │ • CRUD admin     │ │ • Recibe ADMS    │ │ • Pay period gen │ │  │
│  │ │ • Auth           │ │ • Traduce a      │ │ • Cleanup        │ │  │
│  │ │ • Reports        │ │   /events        │ │ • Health check   │ │  │
│  │ └──────────────────┘ └──────────────────┘ └──────────────────┘ │  │
│  │                                                                 │  │
│  │ Capa transversal: Auth, Audit, Tenant Isolation, Cache, Metrics│  │
│  └─────────┬───────────────────┬───────────────────┬─────────────┘  │
│            │                   │                   │                 │
│            ▼                   ▼                   ▼                 │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────┐    │
│  │ RDS Postgres │   │ S3           │   │ Cognito              │    │
│  │ Multi-AZ     │   │ • CSV exports│   │ • User pool          │    │
│  │ • RLS ON     │   │ • Backups    │   │ • JWT signing        │    │
│  │ • TDE        │   │   logs       │   │ • Federation futura  │    │
│  └──────────────┘   └──────────────┘   └──────────────────────┘    │
│                                                                       │
│  Observabilidad: CloudWatch logs + métricas + X-Ray traces           │
│  Seguridad: KMS + Secrets Manager + IAM roles + VPC private subnets  │
└──────────────────────────────────────────────────────────────────────┘
```

## Principios arquitectónicos

### 1. Monolito modular antes que microservicios

El backend es **un solo proceso Spring Boot** desplegado en ECS Fargate. Razones:

- Equipo de 3 ingenieros en 3 meses no puede operar microservicios.
- Las transacciones cross-aggregate (correcciones que disparan recálculo) son triviales en monolito, complicadas con servicios.
- Latency interna cero entre módulos.
- Despliegue: 1 imagen Docker, 1 pipeline.

Internamente el monolito está **modularizado** por bounded context (auth, organization, devices, events, calculation, payroll, audit). Si en v3 algún módulo justifica separarse, está aislado.

Ver [ADR-001](./adrs/ADR-001-multi-tenancy-shared-db-rls.md).

### 2. Multi-tenant con aislamiento defensivo

Tres capas de defensa, no una:

```
Capa 1 — JWT enforcement
  Token user/device contiene tenant_id en claims.
  Ningún endpoint acepta tenant_id en URL ni body.

Capa 2 — Tenant context (ThreadLocal + Spring filter)
  Al inicio del request, filter extrae tenant_id del JWT,
  lo pone en TenantContext (ThreadLocal),
  ejecuta SET app.tenant_id = '...' en la conexión JDBC,
  limpia al final del request.

Capa 3 — PostgreSQL Row-Level Security
  Cada tabla con tenant_id tiene política:
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
  Si un developer escribe query sin filtrar tenant_id,
  Postgres devuelve 0 filas (o falla en escritura).
```

Bug en cualquiera de las 3 capas no expone datos cross-tenant. Solo bug **simultáneo** en las 3 lo haría.

Ver [ADR-001](./adrs/ADR-001-multi-tenancy-shared-db-rls.md).

### 3. Offline-first en captura

La captura de eventos **no debe depender de la red**. Esquema:

```
Hardware ZKTeco K50 Pro:
  Buffer interno hasta ~100K eventos local.
  Reintenta POST a backend con backoff propietario.
  Reloj sincronizado vía heartbeat.

Web Kiosko Mode (fallback):
  Captura genera UUID v7 cliente.
  IndexedDB persiste antes de POST.
  Service Worker maneja queue + retry exponencial.
  POST con header Idempotency-Key = UUID.

Backend:
  UPSERT idempotente por UUID en event_raw.
  Reintentos del cliente son inocuos.
```

Ver detalles en [04-flujos-edge-cases.md](./04-flujos-edge-cases.md).

### 4. Strategy pattern para extensibilidad

Toda capacidad que tendrá variantes en v1.1 / v2 vive detrás de una interfaz Spring desde el MVP:

```java
public interface PayrollExporter {
    String getFormatId();
    boolean supports(Tenant tenant);
    ExportResult export(PayrollPeriod period);
}

@Component class CsvGenericExporter implements PayrollExporter { ... }   // MVP
@Component class ContpaqiExporter implements PayrollExporter { ... }     // v2
@Component class NoiExporter implements PayrollExporter { ... }          // v2

// Spring inyecta Map<String, PayrollExporter>; resolución por tenant.config.exporter_format
```

Mismo patrón para:
- `EventEnricher` (NoOp en MVP, PhotoEnricher en v2)
- `CaptureMethodValidator` (NFC + PIN en MVP, Biometric en v1.1)
- `WebhookPublisher` (NoOp en MVP, HttpWebhookPublisher en v2)
- `NotificationChannel` (Email en MVP, SMS/WhatsApp en v2)

Ver [ADR-005](./adrs/ADR-005-strategy-pattern-extensibilidad.md).

### 5. Eventos crudos inmutables

`event_raw` es **append-only**. Nunca `UPDATE`. Las correcciones son nuevos registros con `capture_method='manual'` que apuntan al original vía `event_correction`. El original queda con `is_valid=false`.

Beneficios:
- Auditoría perfecta (qué pasó, qué se corrigió, por quién, cuándo, por qué).
- Reproducibilidad legal del cálculo.
- Idempotencia natural (UUID = clave única).

Ver [ADR-002](./adrs/ADR-002-eventos-inmutables-correcciones-append-only.md).

### 6. Política versionada con snapshot en cálculo

Cada cambio de política genera un registro en `plant_policy_history` con `effective_from`. Cuando el motor calcula `attendance_day`, **copia el JSON de política completo** a `attendance_day.policy_snapshot`. Doble seguridad:

- Recálculo histórico siempre usa la política vigente en la fecha del evento.
- Aunque alguien modifique `plant_policy_history`, el resultado calculado tiene su copia.

## Stack tecnológico detallado

### Backend
- **Lenguaje**: Java 21 (LTS).
- **Framework**: Spring Boot 3.x.
- **ORM**: Spring Data JPA + Hibernate.
- **Migraciones**: Flyway (SQL puro versionado).
- **Build**: Gradle 8.x con KTS o Maven.
- **Testing**: JUnit 5 + Mockito + Testcontainers (Postgres real en tests de integración).

### Base de datos
- **PostgreSQL 16** en RDS Multi-AZ.
- **TDE** (Transparent Data Encryption) habilitado at-rest.
- **Row-Level Security** habilitado en todas las tablas con `tenant_id`.
- **Backups**: automated 30 días + snapshot manual antes de cierre de periodo.
- **JSONB** para configuración versátil (`plant.policy_override`, `tenant.retention_config`).
- **UUIDs v7** como PKs (mejor inserción en B-tree que v4).

### Frontend
- **React 18** con **Vite** (build tool).
- **TypeScript** estricto.
- **Routing**: React Router.
- **State**: react-query (server state) + Zustand (client state mínimo).
- **UI**: Tailwind CSS + shadcn/ui (componentes accesibles).
- **Auth**: AWS Amplify Auth (cliente Cognito).
- **Internacionalización**: i18next con español MX como default.

### Infraestructura AWS
- **Compute**: ECS Fargate (1 service, 2 tasks min en prod para HA).
- **DB**: RDS Postgres Multi-AZ.
- **Cache**: Caffeine in-process (no Redis en MVP).
- **Storage**: S3 (exports CSV, backups de logs).
- **CDN**: CloudFront (admin web estática).
- **Auth**: Cognito User Pool (admin/supervisor/RH).
- **Secrets**: AWS Secrets Manager + KMS.
- **DNS**: Route 53.
- **Logs**: CloudWatch Logs + Logs Insights.
- **Metrics**: CloudWatch Metrics + custom dashboards.
- **Traces**: X-Ray (100% sampling en `POST /events`, 10% en resto).
- **IaC**: Terraform desde Sprint 0.
- **CI/CD**: GitHub Actions o GitLab CI → ECR → ECS.

### Hardware
- **ZKTeco K50 Pro**: time clock con huella + RFID + PIN, soporta protocolo ADMS Push.
- **Tarjetas RFID**: MIFARE Classic 1K compatibles.
- Detalle: [09-hardware-zkteco.md](./09-hardware-zkteco.md).

## Capas funcionales del monolito

```
┌────────────────────────────────────────────────────────────┐
│ CONTROLLERS (REST + ADMS)                                   │
│  • api/v1/* — JWT auth (user o device)                     │
│  • iclock/* — auth por device serial number                │
│  • Validation, mapping DTO ↔ domain                        │
└────────────────────┬───────────────────────────────────────┘
                     │
┌────────────────────▼───────────────────────────────────────┐
│ APPLICATION SERVICES (use cases)                            │
│  • Coordinan flujos cross-aggregate                         │
│  • Manejan transacciones                                    │
│  • Aplican políticas de autorización                        │
└────────────────────┬───────────────────────────────────────┘
                     │
┌────────────────────▼───────────────────────────────────────┐
│ DOMAIN (entidades y lógica de negocio)                      │
│  • Aggregates: Tenant, Plant, Employee, Event, Day, Period │
│  • Value objects: TenantId, EventId, ShiftHours, Money     │
│  • Domain events: EventCaptured, DayCalculated, etc.        │
└────────────────────┬───────────────────────────────────────┘
                     │
┌────────────────────▼───────────────────────────────────────┐
│ INFRASTRUCTURE                                              │
│  • Repositories (JPA)                                       │
│  • External clients (Cognito, S3, KMS, SES)                 │
│  • Strategy implementations (PayrollExporter, etc.)         │
│  • Schedulers (cron jobs)                                   │
└─────────────────────────────────────────────────────────────┘

Cross-cutting:
  • TenantContext + filter
  • AuditLog interceptor (JPA event listener)
  • Authorization aspect (@RequiresRole, @RequiresPlantAccess)
  • Idempotency interceptor
  • Rate limiting (Bucket4j)
```

## Comunicación entre componentes

| De → Hacia | Protocolo | Auth | Notas |
|---|---|---|---|
| K50 Pro → Backend | HTTPS POST | Device serial validation | Protocolo ADMS propietario |
| Web → Backend | HTTPS REST | JWT Cognito | API Gateway: ALB directo |
| Web Kiosko → Backend | HTTPS REST | Device JWT firmado server | Mismo endpoint /events |
| Backend → RDS | TLS Postgres | IAM database auth o pwd | RLS aplicado por session var |
| Backend → S3 | HTTPS | IAM role | Pre-signed URLs para download |
| Backend → Cognito | HTTPS | IAM | Para validación de JWT |
| Backend → SES | HTTPS | IAM | Emails transaccionales |

## Ambientes

```
dev          ─ una instancia compartida del equipo
              · auto-deploy on merge to main
              · datos sintéticos generados
              · costos optimizados (single-AZ, t3.micro)

staging      ─ pre-producción, datos similares a prod
              · deploy manual con approval
              · usado para demos
              · multi-AZ para validar HA

production   ─ ambiente real
              · deploy manual con approval
              · multi-AZ + read replica si justifica
              · backup retention 30d + snapshots pre-cierre
              · alarmas críticas a Slack/PagerDuty
```

## Observabilidad

### Logs
- **Formato**: JSON estructurado (Logback con Logstash encoder).
- **Campos obligatorios**: `timestamp`, `level`, `tenant_id`, `trace_id`, `request_id`, `user_id` o `kiosko_id`, `message`.
- **Redacción automática**: PIN, NSS, CURP, RFC enmascarados en logs (`***`).
- **Política**: bodies de request/response logueados solo en errores HTTP ≥ 400 (decisión MVP).

### Métricas
- **CloudWatch Custom Metrics** con dimensión `tenant_id`:
  - `events.received` (counter)
  - `events.duplicate` (counter)
  - `events.invalid` (counter)
  - `api.latency` (histogram, p50/p95/p99)
  - `kioskos.online` (gauge)
  - `db.connections.active` (gauge)
  - `calculation.duration_ms` (histogram)

### Alarmas
- p99 latency en `POST /events` > 1s → page on-call.
- Error rate > 1% en cualquier endpoint → ticket.
- Kiosko sin heartbeat > 1h → email a supervisor.
- DB connection pool saturado > 80% → escalar.
- Tenant onboarding manual → log + Slack notification.

### Traces
- **X-Ray** con 100% sampling en `POST /events`.
- 10% sampling en resto.
- Propagación de `X-Trace-Id` end-to-end (kiosko → ALB → app → DB).

## Diagrama de despliegue MVP

```
                           ┌─────────────────┐
                           │ Internet        │
                           └────────┬────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    │               │               │
                    ▼               ▼               ▼
              ┌─────────┐     ┌─────────┐     ┌─────────┐
              │CloudFr. │     │  ALB    │     │   DNS   │
              │admin web│     │/api/+   │     │(Route53)│
              └─────────┘     │/iclock  │     └─────────┘
                              └────┬────┘
                                   │
                ┌──────────────────┼──────────────────┐
                ▼                  ▼                  ▼
            VPC private subnet AZ-a, AZ-b
            ┌──────────────────────────────────┐
            │ ECS Fargate Service              │
            │  Task 1 (AZ-a)  Task 2 (AZ-b)    │
            │  Spring Boot    Spring Boot      │
            └──────────────┬───────────────────┘
                           │
                ┌──────────┼──────────┐
                ▼          ▼          ▼
          ┌─────────┐ ┌────────┐ ┌─────────┐
          │ RDS     │ │ S3     │ │ Secrets │
          │ Multi-AZ│ │exports │ │ Manager │
          └─────────┘ └────────┘ └─────────┘
```
