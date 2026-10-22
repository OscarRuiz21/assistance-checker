# ADR-001: Multi-tenancy con shared DB + RLS

**Estado**: Aceptado
**Fecha**: 2026-04-26
**Decisores**: Tech Lead

## Contexto

El producto es un SaaS multi-tenant donde múltiples empresas (tenants) compartirán la plataforma. Necesitamos definir cómo aislar sus datos.

Tres opciones de aislamiento estándar:

1. **Shared DB + tenant_id en cada tabla** — todas las filas de todos los tenants en la misma DB; columna `tenant_id` distingue.
2. **Shared DB + schema-per-tenant** — una sola DB con N schemas, uno por tenant.
3. **DB-per-tenant** — cada tenant su propia base de datos.

## Decisión

**Shared DB con `tenant_id` en cada tabla, defendido por tres capas: RLS de Postgres + JPA filter + ThreadLocal context.**

## Razonamiento

### Por qué shared DB y no schema/db separado

Para 2-50 tenants, costos y operación favorecen shared:

| Aspecto | Shared DB | Schema-per-tenant | DB-per-tenant |
|---|---|---|---|
| Costo infra | $1 | $1 | $$$ |
| Backups | Simple | Complejo | $$$ |
| Migraciones | 1× | N× | N× |
| Aislamiento | Lógico (RLS) | Lógico (search_path) | Físico |
| Onboarding nuevo tenant | INSERT row | CREATE SCHEMA + migraciones | Provisión nueva instancia |
| Aplicabilidad a sectores regulados (financial, gobierno) | Aceptable con RLS | Mejor | Mejor |

Para nuestro caso (industria privada, no financiero, MVP de 2 tenants creciendo a ~20 en 2 años), el shared DB es la elección correcta.

### Por qué tres capas de defensa

Una sola capa es frágil. Bug en cualquiera = leak. Tres capas independientes:

```
Capa 1: JWT enforcement
  El token de auth contiene tenant_id en claims.
  Endpoints NUNCA aceptan tenant_id desde URL o body.
  Si el cliente intenta inyectar tenant_id distinto, se ignora.

Capa 2: Spring filter + ThreadLocal
  Al inicio del request:
    Filter extrae tenant_id del JWT
    SET app.tenant_id = '...' en la conexión JDBC
    TenantContext.set(tenantId) en ThreadLocal
  Al final del request:
    TenantContext.clear()
    SET app.tenant_id = NULL

Capa 3: PostgreSQL Row-Level Security
  ALTER TABLE event_raw ENABLE ROW LEVEL SECURITY;
  CREATE POLICY tenant_isolation ON event_raw
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
  
  Si developer escribe SELECT sin filtrar tenant_id, Postgres devuelve 0 filas.
  Si intenta INSERT con tenant_id distinto al session, falla.
```

Bug en capa 1 (libra el JWT): capa 2 ya tiene el tenant del JWT.
Bug en capa 2 (no setea ThreadLocal): RLS rechaza queries sin `app.tenant_id`.
Bug en capa 3 (RLS deshabilitado por error): JPA filter aún filtra.

Solo un bug **simultáneo** en las 3 expone datos cross-tenant.

### Por qué tenant_id en TODAS las tablas

Incluso tablas que parecen "no tenant-specific" (ej. `kiosko_heartbeat`) llevan `tenant_id`. Razones:

1. RLS aplica uniformemente, no hay excepciones que documentar.
2. Queries de monitoreo cross-tenant requieren explicitar `SET app.tenant_id = NULL` (operación auditada).
3. Particionamiento futuro por tenant es trivial.

Esto incluye **subtablas 1:1** que viven colgadas de una tabla principal con `tenant_id` (caso de `kiosko_zkteco` y `kiosko_web`, subtipos de `kiosko`). Aunque la integridad referencial garantiza que el `tenant_id` se podría derivar vía JOIN, denormalizarlo es una victoria de defensa-en-profundidad: la política RLS de la subtabla aplica sin necesidad de join, y un bug que rompa la coherencia kiosko↔subtabla no produce data leak entre tenants.

## Consecuencias

### Positivas

- Costo de infra mínimo (1 DB para N tenants).
- Onboarding de tenant: INSERT en una tabla.
- Migraciones únicas, aplicables a todo el sistema en un solo paso.
- Pen-test interno tiene patrón claro: intentar leer datos de otro tenant.

### Negativas

- Complejidad inicial del setup (RLS + filter + ThreadLocal) — ~3-5 días de implementación correcta.
- Riesgo de bug sutil si developer pasa por alguna capa (mitigado con 3 capas).
- Tenants muy grandes (10K+ empleados) compiten por recursos; eventualmente requiere partitioning.
- Queries que deliberadamente cruzan tenants (ej. analytics globales) requieren código especial.

### Casos especiales documentados

- **Super admin endpoints**: jobs y herramientas internas que requieren ver todos los tenants. Se desactiva RLS con `SET app.tenant_id = NULL` y se loguea explícitamente.
- **Onboarding manual**: SQL ejecutado por equipo de soporte con sesión auditada y `SET app.tenant_id = NULL`.
- **Catálogos globales** (ej. `policy_profile is_system=true`): no llevan tenant_id, accesibles a todos.

### Métricas de éxito de la decisión

- 0 leaks cross-tenant en pen-test interno (test obligatorio en CI).
- 0 endpoints CRUD que olviden filtrar tenant_id (test cross-tenant en CI).
- Migraciones aplican uniformemente a todos los tenants en < 5 segundos.

## Alternativas rechazadas

- **Schema-per-tenant**: agrega complejidad de migraciones y operación. Útil cuando los tenants requieren personalizaciones de schema, lo cual no es nuestro caso.
- **DB-per-tenant**: prematuro. Solo justificable cuando hay clientes regulados (financial, gobierno) que lo exijan contractualmente.

## Re-evaluar cuando

- Llegamos a 50+ tenants → considerar particionamiento de eventos por tenant_id.
- Un tenant alcanza 5K+ empleados y empieza a degradar a otros.
- Cliente regulado (banco, gobierno) requiere aislamiento físico.
