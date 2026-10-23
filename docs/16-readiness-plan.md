# 16 — Production Readiness Plan

Fecha del análisis: 2026-10-22 · Commit analizado: `252baa6` (único commit).

## 0. Resumen ejecutivo

El repo es un **demo MVP mono-tenant bien construido** que sigue al pie de la letra `15-demo-mvp-plan.md`, pero **ninguna de las capas de producción descritas en los docs 02, 03, 10, 11 y 14 está implementada**. La documentación está muy por delante del código.

| Área | Readiness (0-5) | Estado en una frase |
|---|---|---|
| Modelo de datos | 1 | 7 de ~25 tablas; sin RLS, sin auditoría, sin retención |
| Autenticación / autorización | 1 | No existe: sin Spring Security, sin login, sin roles |
| Frontend | 2 | Kiosko y admin funcionales; sin tests, sin login, PWA incompleta |
| CI/CD e infra | 1 | Sin pipeline, sin Dockerfiles, sin Terraform, sin perfiles |
| Seguridad | 1 | Todo endpoint es público; PIN de 4 dígitos sin lockout |

**Veredicto:** apto para demo en LAN de confianza. **No apto** para exponerse a internet ni para un piloto con datos reales de empleados (LFPDPPP / LFT Art. 804).

---

## 1. Modelo de datos

**Implementado** (`backend/src/main/resources/db/migration/V1__schema_demo.sql`, `V2__seed_demo.sql`):
`tenant`, `plant`, `employee`, `nfc_card`, `kiosko`, `kiosko_web`, `event_raw`. PK UUID, FKs y `CHECK` correctos; índices en `employee`, `nfc_card` y `event_raw`.

**Multi-tenancy:** las tablas llevan `tenant_id`, pero el tenant está fijo en código:
`AttendanceServiceImpl.java:45` → `DEMO_TENANT_ID = 00000000-...-0001`. No hay RLS, ni filtro Hibernate, ni `SET app.tenant_id`. Hoy no hay fuga porque solo existe un tenant; con un segundo tenant cualquier query nueva sin filtro filtraría datos.

**LFT Art. 804 (inmutabilidad/retención):** no hay `UPDATE/DELETE` en la API, pero nada lo impide a nivel BD; `DemoSeedService.reset()` hace `DELETE FROM event_raw`. Faltan `audit_log`, `event_correction`, `review_queue`, `retention_config`, triggers.

**Zona horaria:** `tenant.timezone`/`plant.timezone` existen pero no se usan; `'America/Mexico_City'` está hardcodeado en SQL y Java.

**Faltan del diseño de `03-modelo-datos.md`:** `app_user`, `user_plant_access`, `shift`, `employee_plant`, `policy_profile`, `plant_policy_history`, `holiday`, `attendance_day`, `pay_period(_summary)`, `biometric_template`, `kiosko_zkteco`, `kiosko_heartbeat`, `device_command`, `idempotency_record`, `audit_log`, `event_correction`, `review_queue`. El motor de cálculo de horas (`05-motor-calculo-horas.md`) no existe en código.

**PII:** `curp`, `rfc`, `nss` en texto plano. Solo `pin_hash` usa bcrypt (pgcrypto).

## 2. Autenticación y autorización

- `build.gradle.kts` no incluye `spring-boot-starter-security` ni librerías JWT/OAuth2. No hay `SecurityFilterChain`, login, `app_user` ni roles.
- **Kiosko:** identifica al empleado por UID de tarjeta (`?u=<UID>` en la URL) + PIN. `PinVerifier.java:22-27` compara con `crypt()` (bien), pero **sin rate-limit ni lockout**; PIN universal `1234` (`application.yml:51`).
- **Dispositivos:** el kiosko no se autentica; cualquiera puede hacer `POST /api/events` con cualquier `cardUid`. El endpoint de pairing (`/api/v1/auth/kiosko/pair`, doc 06) no existe.
- **ZKTeco push adapter (`/iclock/*`):** no implementado.
- `app.demo.auth-token` (`application.yml:52`, default `demo123`) está definido pero **no se usa en ningún lado**.
- **Frontend:** no envía `Authorization`, no guarda tokens; `/admin` no tiene guard.
- **Prometido vs real:** ADR-001 y docs 02/06/10 prometen Cognito + MFA, JWT con `tenant_id`, ThreadLocal + RLS (3 capas), RBAC `super_admin/tenant_admin/rh/supervisor`. Implementado: 0 de 3 capas.

## 3. Cómo funciona el frontend

- **Stack real:** React 19.2, Vite 8, TypeScript 6 (strict), Tailwind 3, React Router 7, Recharts. TanStack Query está instalado pero sin uso (todo es `fetch` + `useState`).
- **Routing** (`src/App.tsx`): kiosko `/k`, `/k/manual`, `/k/error` (eager) y admin `/admin`, `/admin/empleados`, `/admin/empleados/:id` (lazy, para no cargar Recharts en el kiosko).
- **Kiosko:** el sticker NFC abre `/k?u=<UID>` (no usa Web NFC, que Safari iOS no soporta) → `GET /api/employees/by-card/:uid` → selector de tipo de evento + PIN de 4 dígitos → `POST /api/events` → overlay de éxito 2.8 s y regreso a `/k`. `ManualEntry` hace lo mismo con número de empleado.
- **Admin:** KPIs, tabla de eventos y gráfica por hora; actualización en vivo por SSE (`/api/events/stream`) + polling de 60 s; resumen por quincena, detalle diario y export CSV.
- **Build:** `vite build` escribe en `backend/src/main/resources/static`; Spring sirve todo desde el mismo origen. En dev, proxy de Vite a `:8080`. No hay `.env` ni `VITE_*`.
- **Huecos:** 0 tests; sin `ErrorBoundary`; iconos `icon-192/512.png` del manifest no existen en `public/` (la PWA no instala bien); sin cola offline; `user-scalable=no` en `index.html` (accesibilidad); textos sin i18n.

## 4. CI/CD, build e infra

- **No existe** `.github/`, ni otro CI, ni Dockerfiles, ni `infra/` (README: "Terraform — pendiente").
- `docker-compose.yml` solo levanta Postgres 16. `scripts/dev.sh` es solo para desarrollo local.
- Backend: Java 21, Spring Boot 3.5.0, Modulith 1.4.0, Flyway, Actuator.
- **Tests:** solo `BackendApplicationTests` (smoke) y `ModularityTests`. Sin Testcontainers, ArchUnit ni JaCoCo; `14-test-architecture.md` describe una pirámide que no existe.
- **Config:** un único `application.yml` con defaults inseguros (`DB_PASSWORD:assistance_dev`, PIN `1234`, `DEMO_TOKEN:demo123`, CORS abierto); sin perfiles `dev/staging/prod` ni fail-fast.
- **Observabilidad:** Actuator expone `health, info, metrics, modulith` sin protección; logs `DEBUG` en `com.assistance`; sin Prometheus ni logs estructurados.

## 5. Seguridad existente

| Sev. | Hallazgo | Ubicación |
|---|---|---|
| Crítico | Todos los endpoints públicos, incluidos `POST /api/demo/reset` (borra eventos) y `GET /api/events/export.csv` (PII) | `DemoController.java`, `EventController.java:50-80` |
| Crítico | Sin aislamiento multi-tenant (tenant hardcodeado, sin RLS) | `AttendanceServiceImpl.java:45` |
| Crítico | Kioskos/dispositivos no autenticados; adapter ZKTeco inexistente | — |
| Alto | PIN de 4 dígitos sin lockout → fuerza bruta de 10 000 combinaciones trivial | `PinVerifier.java`, `AttendanceServiceImpl.verifyPinIfNeeded` |
| Alto | CORS con wildcards (`*.ngrok-free.app`, `*.trycloudflare.com`, LAN) + `allowCredentials(true)` | `CorsConfig.java`, `application.yml:56` |
| Alto | Sin headers de seguridad (HSTS, CSP, X-Frame-Options, nosniff) | — |
| Alto | Actuator `metrics`/`modulith` expuestos sin control | `application.yml:38` |
| Medio | `GlobalExceptionHandler` incompleto; posible fuga de mensajes internos en 500 | `GlobalExceptionHandler.java` |
| Medio | UID de tarjeta (equivale a credencial) en query string → logs, historial | `NfcEntry.tsx` |
| Medio | CURP/RFC/NSS sin cifrado de columna | `V1__schema_demo.sql:29-43` |
| Bajo | Credenciales de dev en claro (compose + defaults yml) | `docker-compose.yml:7` |

**Positivo:** SQL siempre parametrizado (sin inyección detectada), PIN con bcrypt, `.gitignore` excluye `application-prod.yml` y `.env*`, `package-lock.json` comiteado, Spring Modulith ya impone límites de módulos.

---

## 6. Plan de readiness

### Fase 0 — Contención (antes de cualquier URL pública) · ~1 semana
1. Perfiles Spring `local` / `demo` / `prod`; en `prod` fallar al arrancar si `DB_PASSWORD`, `CORS_ORIGINS` o secretos quedan en default.
2. Desactivar `DemoController` y `DemoSeedService` fuera del perfil `demo` (`@Profile("demo")`).
3. Restringir Actuator a `health` en público; resto por puerto de management interno.
4. CORS por lista explícita de orígenes en `prod`.
5. `server.error.include-message=never`, `include-stacktrace=never`; ampliar `GlobalExceptionHandler`.
6. Agregar iconos PWA faltantes.

### Fase 1 — Identidad y acceso · ~3 semanas
1. Agregar Spring Security + resource server OAuth2 (Cognito, como dice ADR/doc 10) para usuarios admin; MFA para `tenant_admin`.
2. Migración `app_user`, `user_plant_access`; roles `super_admin/tenant_admin/rh/supervisor` con `@PreAuthorize`.
3. Pairing de kiosko (`/api/v1/auth/kiosko/pair`) → token de dispositivo de vida corta, ligado a `kiosko_id` + `tenant_id`.
4. Rate-limit + lockout de PIN por empleado y por kiosko (p. ej. 5 intentos → bloqueo 15 min) y en `by-card/{uid}`.
5. Eliminar PIN universal; flujo de alta/reset de PIN por empleado.
6. Frontend: login admin, guard de rutas, token en memoria + refresh en cookie `httpOnly` (no `localStorage`).
7. Headers de seguridad (HSTS, CSP, `frame-ancestors 'none'`, nosniff).

### Fase 2 — Aislamiento multi-tenant y datos · ~3 semanas
1. `TenantContext` (ThreadLocal) poblado desde el JWT; `SET LOCAL app.tenant_id` por transacción.
2. RLS en todas las tablas con `tenant_id` + rol de BD de la app sin `BYPASSRLS`.
3. Quitar `DEMO_TENANT_ID` / `DEMO_PLANT_ID` / `DEMO_KIOSKO_ID` del código.
4. Usar `plant.timezone` en todos los cálculos.
5. Tests de aislamiento: dos tenants con Testcontainers; deben fallar si una query cruza tenants.

### Fase 3 — Cumplimiento LFT / LFPDPPP · ~3 semanas
1. `event_raw` append-only reforzado: trigger que bloquea `UPDATE/DELETE`; correcciones solo vía `event_correction` + `review_queue`.
2. `audit_log` con triggers (quién leyó, exportó o corrigió).
3. Retención: `tenant.retention_config`, job de archivado (5 años eventos / 7 años auditoría según doc 03).
4. Cifrado de columna de CURP/RFC/NSS (pgcrypto + clave en KMS); redacción de PII en logs.
5. Export CSV restringido por rol/planta y auditado.
6. Aviso de privacidad y registro de consentimiento si se agregan biométricos.

### Fase 4 — CI/CD e infra · ~2 semanas (en paralelo con Fase 1)
1. Dockerfile multi-stage (build del front → bootJar → imagen JRE 21 distroless).
2. GitHub Actions:
   - **PR:** `npm ci && npm run lint && tsc -b` · `./gradlew test` (incluye `ModularityTests`) · `npm audit` / OWASP dependency-check · gitleaks.
   - **main:** tests de integración con Testcontainers · build y push de imagen (tag por SHA) · deploy automático a staging.
   - **release:** deploy a prod con aprobación manual + migraciones Flyway en job separado.
3. Terraform en `infra/`: Lightsail (demo) → ECS Fargate + RDS Multi-AZ + ALB con TLS + Secrets Manager (prod).
4. Observabilidad: logs JSON, Micrometer → Prometheus/CloudWatch, alertas sobre lockouts de PIN, 5xx y retraso de eventos.
5. Backups con PITR y prueba de restauración (runbook doc 11).

### Fase 5 — Producto y calidad · continuo
1. Motor de cálculo de horas (`shift`, `policy_profile`, `holiday`, `attendance_day`, `pay_period`).
2. Push adapter ZKTeco con allowlist de número de serie + IP, TLS e idempotencia (`idempotency_record`); no copiar el modelo "sin auth" del doc 09.
3. Cola offline en el kiosko (IndexedDB + reintento idempotente).
4. Tests: unitarios del motor de horas, `@WebMvcTest` de controllers, Playwright para el flujo kiosko; cobertura mínima con JaCoCo (doc 14 pide 80%).
5. Frontend: `ErrorBoundary`, adoptar TanStack Query o quitarlo, quitar `user-scalable=no`.

### Criterios de salida

| Hito | Requisito mínimo |
|---|---|
| Demo pública (internet) | Fase 0 completa + auth de admin (Fase 1.1) + lockout de PIN (Fase 1.4) |
| Piloto con 1 cliente real | Fases 0-1 completas, Fase 3.1-3.2, Fase 4.1-4.2, backups |
| Producción multi-tenant | Fases 0-4 completas + pentest externo + tests de aislamiento en verde en CI |

## Estado de implementación — Fase 4

Implementado por el Agente C (rama `feat/readiness-phases-0-4`), en paralelo
con Fases 0-3 (Agentes A/B). Detalle completo en
[17-ci-cd-y-despliegue.md](./17-ci-cd-y-despliegue.md).

**Hecho:**
- `Dockerfile` multi-stage (frontend `node:20-alpine` → backend
  `eclipse-temurin:21-jdk` con `bootJar -x test` y cache de dependencias →
  imagen final `eclipse-temurin:21-jre-alpine`, usuario no-root, jar en
  capas, `JAVA_OPTS` con `MaxRAMPercentage=75`, `HEALTHCHECK` contra
  `/actuator/health` en el management port) + `.dockerignore`.
- `docker-compose.yml`: Postgres sin cambios de comportamiento + servicio
  `app` nuevo bajo compose profile `full` (no afecta `./scripts/dev.sh` ni
  `docker compose up` por defecto).
- `scripts/dev.sh`: `set -euo pipefail`, variables de entorno del contrato
  exportadas para perfil `local`, espera activa a `/actuator/health` antes
  de levantar el frontend.
- `backend/src/main/resources/application-prod.yml`: logging JSON (ECS),
  management port separado con probes de liveness/readiness, Hikari
  dimensionado, `forward-headers-strategy=native`, sin defaults de
  secretos.
- `.github/workflows/ci.yml` (jobs `frontend`, `backend`, `integration`,
  `security`, `docker`) y `.github/workflows/deploy.yml` (staging
  automático, producción con aprobación de GitHub Environment, OIDC a AWS,
  deploy a ECS). `.github/dependabot.yml` para gradle/npm/github-actions/docker.
- `infra/` en Terraform: `modules/{network,database,app}` +
  `envs/{staging,prod,demo}` — VPC, ALB HTTPS con redirect 80→443, ECS
  Fargate, RDS Postgres 16 (Multi-AZ + KMS + PITR en prod), Secrets
  Manager, CloudWatch logs + alarmas (5xx, CPU/memoria ECS, CPU/storage
  RDS), rol IAM OIDC para GitHub Actions, ECR. Backend S3+DynamoDB
  documentado como bootstrap comentado.
- `docs/17-ci-cd-y-despliegue.md` nuevo; `README.md` actualizado (ya no
  dice "infra/ — pendiente").

**Validado en esta máquina (sin Docker/AWS reales):**
- `bash -n scripts/dev.sh` → OK.
- YAML de workflows, `dependabot.yml` y `docker-compose.yml` parseado con
  `python3 -c "import yaml..."` → OK.
- JSON de `infra/ecs/task-definition.*.json` parseado → OK.
- `terraform fmt -recursive` aplicado sin diffs pendientes.
- `terraform init -backend=false && terraform validate` en `envs/staging`,
  `envs/prod` y `envs/demo` → `Success! The configuration is valid.` en
  los tres.
- No se corrió `docker build`, `hadolint`, ni `terraform plan/apply`
  (sin Docker ni cuenta AWS disponibles en este entorno).

**Pendiente / depende de otros agentes o decisiones de producto:**
- `AUTH_ISSUER_URI` real depende de que exista el Cognito user pool (Fase 1,
  fuera de este alcance).
- Promoción de imagen GHCR→ECR no implementada (`deploy.yml` asume la
  imagen ya en ECR); ver decisión documentada en 17 §6.
- `terraform plan/apply` reales no ejecutados; revisar nombres de
  dominio/ACM y backend remoto antes del primer `apply`.
- `npm audit` es no bloqueante temporalmente (continue-on-error documentado).

## Estado de implementación — Fases 2-3

Implementado por el Agente B (rama `feat/readiness-phases-0-4`), en paralelo
con Fase 0-1 (Agente A) y Fase 4 (Agente C, ya cerrada arriba).

**Hecho:**
- **Aislamiento multi-tenant (Fase 2):** RLS (`ENABLE` + `FORCE ROW LEVEL
  SECURITY` + política `tenant_id = current_setting('app.tenant_id', true)`)
  en `tenant`, `plant`, `employee`, `nfc_card`, `kiosko`, `kiosko_web`,
  `event_raw`, `event_correction`, `review_queue`, `audit_log`,
  `event_raw_archive` (`V3`, `V5`-`V7`). Rol `assistance_app`
  `NOSUPERUSER NOBYPASSRLS` creado idempotentemente en `V3`; Flyway sigue
  migrando como el rol superusuario (`${DB_MIGRATION_USER}`). Propagación de
  tenant vía `TenantAwareDataSource` (envuelve el bean `dataSource` con
  `TenantDataSourceBeanPostProcessor`): hace
  `set_config('app.tenant_id'/'app.actor_user_id'/'app.actor_kiosko_id', ...)`
  en cada `getConnection()` desde `TenantContext` (ThreadLocal poblado por A
  desde JWT/token de kiosko), y limpia esas GUCs al `close()` de la conexión
  (vía un proxy dinámico) para que el pool nunca filtre tenant entre
  checkouts. `AttendanceServiceImpl`/`DeviceServiceImpl`/`EmployeeServiceImpl`
  filtran explícitamente por `tenant_id` + `plantIds` del scope como defensa
  adicional a RLS (no solo confían en la política de BD). `EventStreamHub`
  solo hace broadcast de un evento SSE a un suscriptor si
  `tenant_id` coincide y (si el suscriptor está acotado) la planta también.
  `TimezoneResolver` reemplaza el `'America/Mexico_City'` hardcodeado por
  `plant.timezone` → `tenant.timezone` → fallback documentado (mismo
  default que la columna en BD). `DemoSeedService` sigue con un
  `DEMO_TENANT_ID` fijo, pero documentado y correcto: solo corre bajo
  `@Profile({"local","demo"})`, vía `TenantContext.runAs(TenantScope.system(...))`.
- **`event_raw`/`event_correction` append-only reforzado (Fase 3.1):**
  trigger `event_raw_append_only_guard()` bloquea `DELETE` siempre y
  `UPDATE` salvo a las columnas de invalidación; función SECURITY DEFINER
  `invalidate_event_raw(...)` es la única vía de invalidar (el rol de app
  nunca tiene `GRANT UPDATE` sobre `event_raw`). `event_correction` es
  100% append-only (ni siquiera invalidación). Reset de demo usa
  `demo_reset_events(tenant)` (SECURITY DEFINER, bypass del guard solo
  dentro de su propia transacción, solo para el tenant de demo — nunca un
  `DELETE` sin `WHERE`). `EventCorrectionService` (sin endpoint todavía,
  pendiente del modelo de roles de A) implementa `invalidate`/`adjust` según
  ADR-002.
- **`audit_log` transversal (Fase 3.2, `V6`):** tabla append-only (`REVOKE
  UPDATE, DELETE` + trigger que siempre rechaza) con RLS; función
  `write_audit_log()` + triggers `AFTER INSERT OR UPDATE OR DELETE` en
  `employee`, `nfc_card`, `kiosko`, `event_correction` (las tablas bajo
  alcance de este agente; `plant`/`shift`/etc. del modelo completo de doc 03
  quedan para cuando esos módulos existan). Para acciones que no son un
  INSERT/UPDATE/DELETE de fila (export CSV, lectura puntual de PII),
  `compliance::api.AuditApi` (`AuditApiImpl`, inserta directo con
  `JdbcTemplate`) — ya se usa en `AttendanceServiceImpl.listForExport(...)`.
  Se corrigió un bug de compilación en `AuditApiImpl` (variable `action`
  fuera de alcance en un método privado).
- **Retención (Fase 3.3, `V7`):** `tenant.retention_config JSONB DEFAULT
  {"events_years":5,"audit_years":7}`; tabla `event_raw_archive` (misma
  forma que `event_raw` + `archived_at`, también RLS + append-only); función
  SECURITY DEFINER `archive_and_purge_events(tenant_id, cutoff)` que
  **siempre** inserta en el archivo antes de borrar de `event_raw` (nunca
  `DELETE` sin archivar), reutilizando el mismo mecanismo de bypass del
  guard append-only que `demo_reset_events`. `list_tenant_ids_for_retention()`
  es la única función que cruza tenants deliberadamente (SECURITY DEFINER
  con dueño superusuario, para que el job pueda iterar sin necesitar "ver
  todos los tenants" vía RLS normal). `compliance.internal.service.RetentionService`
  es un `@Service` con `@ConditionalOnProperty(prefix="app.retention",
  name="enabled", havingValue="true")` — **deshabilitado por defecto** sin
  tocar `application*.yml` (la propiedad simplemente no existe ahí); si se
  habilita, corre con `@Scheduled(cron = "${app.retention.cron:0 30 2 * * *}")`
  y procesa cada tenant con `TenantContext.runAs(TenantScope.system(tenantId), ...)`.
  `@EnableScheduling` ya estaba a nivel de aplicación (lo usa `EventStreamHub`
  para sus heartbeats SSE), así que no hizo falta agregarlo.
- **Cifrado de PII (Fase 3.4, `V8` + `V9` Java):** columnas `curp_enc`/
  `rfc_enc`/`nss_enc BYTEA` (`V8`); `PiiCipher` (AES-256-GCM, formato
  `IV(12) || ciphertext+tag`, clave = SHA-256 de `app.pii.key`/`APP_PII_KEY`,
  con fallback inseguro solo-dev si la propiedad falta, con WARN fuerte) +
  `PiiAttributeConverter` (JPA `@Converter`, con fallback estático para el
  caso borde de que Hibernate instancie el converter sin pasar por el
  contenedor de Spring). `V9__EncryptExistingPiiData` es una migración
  **Java** (no SQL) porque necesita `PiiCipher` con la clave real inyectada
  por Spring — Flyway (rol de migración, sin contexto de Spring) no puede
  ver `APP_PII_KEY`. `Employee` ya no mapea las columnas en claro
  (`curp`/`rfc`/`nss`): JPA solo ve las `*_enc`. Nunca se loguea PII (los
  mensajes de error de `PiiCipher` no incluyen el valor en claro).
- **Verificaciones del punto 4 del encargo:** sin `DEMO_TENANT_ID`/
  `DEMO_PLANT_ID`/`DEMO_KIOSKO_ID` fuera de `demo/internal/**` (el único que
  queda es el `TENANT` de `DemoSeedService`, dentro de su alcance
  documentado); `TimezoneResolver` reemplaza el hardcode de zona horaria en
  todo el código bajo este agente; `AuditApi.record` sí se usa en el export
  CSV; `PiiCipher`/`PiiAttributeConverter` cifran CURP/RFC/NSS sin loguear
  el valor; `TenantAwareDataSource` limpia las GUCs de tenant al cerrar cada
  conexión antes de devolverla al pool (ver `wrap(...)`/`resetTenantGucs(...)`).
  **Hallazgo pendiente para A** (no se tocó, son archivos `*Controller.java`):
  `EmployeeStatsController`, `DashboardController` y `EventController`
  siguen con `ZoneId TZ_MX = ZoneId.of("America/Mexico_City")` hardcodeado
  para resolver los límites de fecha por defecto de los filtros de rango
  (`?from=&to=`) antes de llamar al servicio — el cálculo de negocio en sí
  (dashboard/summarize/dailyBreakdown, dentro de `AttendanceServiceImpl`) ya
  usa `TimezoneResolver` correctamente; solo el "día de hoy por defecto" del
  controller queda con TZ fija.
- **Tests:**
  - Integración (`@Tag("integration")`, Testcontainers `postgres:16-alpine`,
    conectando con `assistance_app` no-superusuario — igual que producción):
    `TenantIsolationIntegrationTest` (aislamiento JPA vía `EmployeeApi` y SQL
    nativo entre dos tenants; sin `TenantContext` → 0 filas en
    `employee`/`plant`/`kiosko` y `ApiException` en la capa de servicio;
    `UPDATE`/`DELETE` directos sobre `event_raw` fallan para el rol de app;
    `audit_log` registra un `UPDATE` sobre `employee` y no se filtra al otro
    tenant; `curp_enc` nunca contiene el CURP en claro y la columna legacy
    `curp` queda `NULL`) y `RetentionArchivingIntegrationTest`
    (`archive_and_purge_events` copia a `event_raw_archive` antes de borrar
    de `event_raw`, respeta el cutoff, y `list_tenant_ids_for_retention`
    cruza tenants deliberadamente).
  - Unitarios sin Docker: `TimezoneResolverTest`, `PiiCipherTest`
    (round-trip, no-determinismo del IV, falla con clave incorrecta,
    `null`-safety) y `PlantScopeFilteringTest` (contrato de
    `TenantScope.canAccessPlant`/`plantIds` usado por los tres servicios).
- **Verificación ejecutada en esta máquina (sin Docker, por eso
  `integrationTest` no corrió):**
  - `./gradlew compileJava compileTestJava` → `BUILD SUCCESSFUL`.
  - `./gradlew test` (excluye `@Tag("integration")`, incluye
    `ModularityTests`) → `BUILD SUCCESSFUL`, todos los tests en verde
    (incluidos los nuevos de este agente y los de A/C ya presentes en el
    árbol).
  - Revisión manual del SQL de `V6`-`V8`: orden correcto (columnas `*_enc`
    existen en `V8` antes de que `V9` las use), `REVOKE`/`GRANT` explícitos
    donde corresponde, triggers idempotentes vía `CREATE OR REPLACE
    FUNCTION` (las migraciones Flyway en sí no son re-ejecutables, pero las
    funciones que definen sí lo son si algún día se necesita un
    `repair`/`baseline`).

**Pendiente / depende de otros agentes:**
- `EmployeeStatsController`/`DashboardController`/`EventController`
  (Agente A) con `TZ_MX` hardcodeado para resolver rangos de fecha por
  defecto — ver hallazgo arriba.
- `integrationTest` (Testcontainers) no se ejecutó en esta máquina por falta
  de Docker; el código se revisó manualmente pero falta la corrida real.
- El motor de cálculo de horas, `shift`/`policy_profile`/`holiday`/
  `attendance_day`/`pay_period` (doc 03 §5/§7) siguen fuera de alcance
  (Fase 5).
- `app.retention.enabled=true` y el `cron` de retención no se han fijado en
  ningún `application*.yml` (decisión de producto/ops, fuera de los
  archivos que este agente puede tocar); hoy el job está apagado en todos
  los perfiles.

## Estado de implementación — Fases 0-1

Implementado por el Agente A (rama `feat/readiness-phases-0-4`), en paralelo
con Fase 2-3 (Agente B, arriba) y Fase 4 (Agente C, ya cerrada).

**Hecho:**
- **Contención (Fase 0):** `SecurityConfig` stateless (sin sesión, CSRF
  deshabilitado porque no hay cookies de sesión de navegador salvo la de
  refresh, que es `httpOnly`+`Secure`+`SameSite=Strict` y no se envía por
  CORS cross-site). Rutas públicas acotadas a `/k`, `/k/**` (estáticos SPA
  de kiosko), `/api/auth/login|refresh|logout` y `/actuator/health`; el
  resto de `/api/**` requiere autenticación. Dos `JwtDecoder` por perfil:
  `prod` valida contra Cognito (`JwtDecoders.fromIssuerLocation`,
  `AUTH_ISSUER_URI`) y mapea `custom:tenant_id`/`cognito:groups`; `!prod`
  usa HS256 local (`APP_JWT_SECRET`) vía Nimbus. `KioskAuthFilter` corre
  antes del filtro de usuario/contraseña y autentica por cabecera
  `Authorization: Kiosk <token>` (token opaco de 32 bytes, se compara contra
  el hash SHA-256 guardado — nunca el valor en claro). `ProdFailFastValidator`
  (`@Profile("prod")`, `HIGHEST_PRECEDENCE`) revienta el arranque si faltan
  `DB_APP_PASSWORD`/`APP_PII_KEY`/`CORS_ORIGINS`/`AUTH_ISSUER_URI`, o si
  `APP_JWT_SECRET` SÍ está seteado en prod (no debería existir fuera de
  local/demo). `CorsConfig` usa una lista explícita de orígenes
  (`app.cors.allowed-origins`), nunca `*`; los wildcards de LAN/ngrok para
  pruebas en campo solo existen en `application-local.yml`/
  `application-demo.yml`. `GlobalExceptionHandler` centraliza 401/403/400 y
  convierte cualquier excepción no controlada en un 500 genérico (el detalle
  real se loguea server-side, nunca se expone al cliente); `server.error.
  include-*=never` además apaga el whitelabel error de Spring Boot.
  Cabeceras de seguridad (CSP compatible con los assets de Vite/Workbox,
  `frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: no-referrer`, HSTS) agregadas en `SecurityConfig`.
  Actuator: solo `health` es público; el resto vive en
  `management.server.port=8081` — **decisión adicional de este agente**: con
  el management port separado, Spring Boot deja de exponer
  `/actuator/health` en el puerto principal, pero el health check de
  ALB/ECS de Fase 4 (`infra/modules/app/alb.tf`, archivo de C, no se tocó)
  sí pega al puerto principal. Se agregó `PublicHealthController`
  (`shared/internal/web/`, dentro de mi alcance) que expone
  `GET /actuator/health` en el puerto principal delegando al
  `HealthEndpoint` real de Spring Boot, sin modificar nada de Fase 4.
- **Identidad y acceso (Fase 1):** login/refresh/logout en
  `identity::internal` (`@Profile({"local","demo"})` — en prod la identidad
  vive en Cognito, no hay endpoint de login propio); `AuthServiceImpl` hace
  bcrypt + lockout de 10 fallos/15 min por email antes de permitir login;
  `JwtIssuer` (HS256 Nimbus, exige secreto ≥32 bytes) emite access (15 min)
  y refresh (7 días); el refresh viaja en cookie `httpOnly`+`Secure`+
  `SameSite=Strict`, nunca en el body ni en `localStorage`. Pairing de
  kiosko (`KioskPairingController`, solo `tenant_admin`) genera un token
  opaco de 32 bytes y guarda únicamente su hash SHA-256
  (`V12__identity_kiosko_device_token.sql`). Lockout de PIN: 5 fallos por
  empleado/15 min en `PinVerifier` (módulo `employee`, archivo dentro de mi
  alcance) y 20 fallos por kiosko/15 min en `EventController` — ambos
  responden `429 PIN_LOCKED`. Se eliminaron `app.demo.universal-pin` y
  `app.demo.auth-token` (verificado con grep, ya no existen en el árbol).
  RBAC vía `@PreAuthorize`: export CSV solo `tenant_admin`/`rh`; endpoints
  de demo (`DemoController`) solo `tenant_admin` y únicamente bajo
  `@Profile({"local","demo"})`; dashboard/resúmenes para
  `super_admin|tenant_admin|rh|supervisor`; rutas de kiosko gateadas por
  `hasRole('kiosk')` en `SecurityConfig`. Migraciones `V10`-`V12`
  (`app_user`, `user_plant_access`, `kiosko_device_token`) con RLS
  `ENABLE`+`FORCE`+política `tenant_isolation` estándar, excepciones
  documentadas vía función `SECURITY DEFINER` (`find_app_user_by_email`,
  necesaria porque el login ocurre *antes* de tener `TenantContext`) y por
  índice único de `token_hash` (autenticación de kiosko, mismo motivo), y
  `GRANT` explícito al rol `${app_db_user}`. Seed de demo (`V20`, en
  `db/demo`, solo se carga si el perfil activo agrega
  `classpath:db/demo` a `flyway.locations`): usuario
  `admin@demo.mx` / `Demo1234!` (bcrypt, rol `tenant_admin`) y un kiosko
  ya emparejado (token `DEMO-KIOSK-TOKEN-PLEASE-CHANGE-0001`, guardado
  como hash) para el kiosko `eeee0001-0000-0000-0000-000000000001` del
  tenant `00000000-0000-0000-0000-000000000001`.
- **Frontend (dentro de mi alcance completo):** `lib/auth.ts` guarda el
  access token solo en memoria (nunca `localStorage`) y usa la cookie
  `httpOnly` de refresh para renovarlo en silencio; `lib/api.ts` envuelve
  `fetch` con un único reintento tras refresh en 401; `lib/sse.ts` implementa
  SSE autenticado a mano (fetch + `ReadableStream`, porque `EventSource`
  nativo no puede mandar `Authorization`); `lib/kiosk.ts` guarda el token de
  kiosko en `localStorage` (dispositivo dedicado, no es una sesión de
  usuario) y lo manda como `Authorization: Kiosk <token>`. `RequireAuth`
  intenta un refresh silencioso al montar si no hay token en memoria (p.ej.
  recarga de página). `/k/pair` (`KioskPair.tsx`) consume el flujo de
  pairing. El parámetro `?u=` de los QR/deep-links de kiosko se limpia con
  `history.replaceState` tras leerlo, para que no quede en el historial del
  navegador. Iconos/manifest PWA los genera `vite-plugin-pwa` en build
  (`vite.config.ts`), no hace falta un `manifest.json` a mano.
- **Hallazgo cruzado resuelto (ciclo de módulos):** `ModularityTests`
  fallaba por un ciclo `device → shared → identity → device`: `shared`
  (`KioskAuthFilter`/`SecurityConfig`) dependía de
  `identity.api.KioskTokenService`, pero `identity` depende de
  `device.api`, que a su vez depende de `shared.api`. Se movió la interfaz
  `KioskTokenService` de `identity.api` a `shared.api` (ya un
  `@NamedInterface("api")` establecido) — ahora `identity` implementa un
  puerto definido por `shared`, en vez de que `shared` dependa de
  `identity`. `ModularityTests` pasa después del cambio.
- **Bug corregido en frontend (encargo explícito):**
  `EmployeeDetail.tsx` usaba `api.exportCsvUrl(...)` + `<a href>`, una API
  que ya no existe (el export requiere `Authorization` y no puede ser un
  link directo). Se reemplazó por una descarga vía `fetch` autenticado +
  `Blob` + link temporal (`handleExportCsv`), con estado de carga/errores.
- **Tests nuevos (sin Docker):**
  - `SecurityConfigTest` (`@WebMvcTest` + `SecurityProbeController` de
    prueba): 401 sin token, 403 con rol incorrecto, 200 con rol correcto,
    rutas de kiosko (401 sin cabecera/token inválido, 200 con token
    válido vía `KioskTokenService` mockeado).
  - `PinLockoutTest`: lockout a los 5 fallos, reset tras éxito, PIN
    vacío/nulo rechazado sin pegarle a la BD.
  - `ProdFailFastValidatorTest`: cada variable faltante individual y la
    combinación.
  - `JwtIssuerTest` (extra, no pedido explícitamente): secreto corto
    rechazado, round-trip de refresh, un access token no sirve como
    refresh, secreto incorrecto y token basura fallan limpio.
  - `BackendApplicationTests.contextLoads` ahora tiene `@Tag("integration")`
    (requiere Docker/Testcontainers, se excluye del `test` normal).
- **Lint de frontend (16 errores de `eslint-plugin-react-hooks` v7,
  reglas nuevas derivadas del React Compiler):** se corrigieron todos.
  Notas de las correcciones menos obvias: `PinPad.tsx` y
  `EventsTable.tsx` reemplazaron un `useEffect` que hacía `setState`
  síncrono por el patrón "ajustar estado durante el render" (comparar un
  valor de prop/derivado contra su versión anterior guardada en estado);
  `sse.ts` movió la actualización de un `ref` (que antes se mutaba
  directamente en el cuerpo del componente) a un `useEffect` sin
  dependencias; los cuatro "fetch al montar" (`Dashboard.tsx`,
  `EmployeeDetail.tsx`, `Employees.tsx`) se arreglaron envolviendo la
  llamada en una función local (`const tick = () => void refresh()`) para
  que el analizador no vea una llamada directa a la función memoizada
  dentro del cuerpo del efecto; `RequireAuth.tsx` eliminó la rama
  síncrona redundante del efecto (el estado inicial ya cubre el caso
  "ya autenticado al montar" vía `useState(isAuthenticated)`).
- **Hallazgo de Agente B atendido:** `DashboardController`,
  `EmployeeStatsController` y `EventController` (mis archivos) tenían
  `ZoneId TZ_MX = America/Mexico_City` hardcodeado para resolver los
  rangos de fecha por defecto de los filtros (`?from=&to=`, incluido el
  formateo del CSV de export). Se reemplazó por
  `TimezoneResolver.resolve(TenantContext.require())` (servicio de B,
  mismo módulo `attendance`, visible porque los tres controllers viven en
  `attendance.internal.web`).

**Verificación ejecutada en esta máquina (sin Docker):**
- `cd backend && ./gradlew compileJava compileTestJava test` →
  `BUILD SUCCESSFUL` (incluye `ModularityTests` en verde).
- `cd frontend && npm run lint` → 0 errores.
- `cd frontend && npx tsc -b` → sin errores.
- `cd frontend && npm run build` → build exitoso, assets generados en
  `backend/src/main/resources/static/` (incluye `manifest.webmanifest`,
  `sw.js` del PWA plugin).

**Pendiente / depende de otros agentes o decisiones de producto:**
- `integrationTest` (Testcontainers) no se corrió en esta máquina por
  falta de Docker; solo se verificó compilación + tests unitarios/
  `@WebMvcTest`.
- MFA para `tenant_admin` (mencionado en doc 10) depende de la
  configuración real del Cognito User Pool en prod (Fase 4/infra), no es
  algo que se pueda activar solo desde este código.
- `AUTH_ISSUER_URI` real (Cognito) no existe todavía; el `JwtDecoder` de
  prod está implementado pero sin un user pool real contra el que probar.
- Falta un endpoint para `EventCorrectionService` (mencionado como
  pendiente por B, depende del modelo de roles — ya está definido en mi
  alcance vía `@PreAuthorize`, puede construirse en un siguiente sprint).
