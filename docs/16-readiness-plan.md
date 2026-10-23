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
