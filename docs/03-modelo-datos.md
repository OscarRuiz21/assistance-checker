# Modelo de datos

## Convenciones globales

- **Motor**: PostgreSQL 16, RDS Multi-AZ con TDE.
- **PKs**: `UUID v7` (time-ordered, mejor que v4 para B-tree). Generados client-side cuando hay flujo offline.
- **Multi-tenant**: `tenant_id UUID NOT NULL` en todas las tablas (excepto `tenant` y catálogos globales). RLS habilitado.
- **Soft delete**: `deleted_at TIMESTAMPTZ NULL`. Borrado físico solo por job de retención según `tenant.retention_config`.
- **Auditoría base**: `created_at`, `updated_at`, `created_by`, `updated_by` en tablas mutables.
- **Fechas**: `TIMESTAMPTZ` para todo lo que sea instante. `DATE` para fechas contables (ej. `business_date`, `holiday.date`).
- **Migraciones**: Flyway con SQL puro, archivo por cambio. Nombre `V<n>__<descripcion>.sql`.
- **Naming**: snake_case en tablas y columnas.

## Diagrama de dominios

```
                        ┌──────────┐
                        │  TENANT  │
                        └────┬─────┘
                             │
       ┌─────────────────────┼─────────────────┬───────────────┐
       ▼                     ▼                 ▼               ▼
  ┌─────────┐          ┌──────────┐      ┌──────────┐    ┌──────────┐
  │APP_USER │          │  PLANT   │      │ EMPLOYEE │    │ HOLIDAY  │
  └─────────┘          └────┬─────┘      └────┬─────┘    └──────────┘
                            │                 │
              ┌─────────────┼──────────┐      │
              ▼             ▼          ▼      ▼
         ┌────────┐    ┌────────┐ ┌──────────────────┐
         │ KIOSKO │    │ SHIFT  │ │ EMPLOYEE_PLANT   │ (M:N)
         │ (base) │    └────────┘ │ NFC_CARD         │
         └───┬────┘               │ BIOMETRIC_TPL    │
             │ 1:1 según type     └──────────────────┘
       ┌─────┴────────┐
       ▼              ▼
 ┌────────────┐ ┌────────────┐
 │KIOSKO_ZKT. │ │ KIOSKO_WEB │
 └─────┬──────┘ └────────────┘
       │ (solo K50, ADMS)
       ▼
      ┌─────────────────────────────┐
      │ EVENT_RAW (append-only)     │
      │ EVENT_CORRECTION            │
      │ REVIEW_QUEUE                │
      └────────────┬────────────────┘
                   │ (job nocturno)
                   ▼
      ┌─────────────────────────────┐
      │ ATTENDANCE_DAY (calculado)  │
      │ PAY_PERIOD                  │
      │ PAY_PERIOD_SUMMARY          │
      └─────────────────────────────┘

POLICY_PROFILE ──► PLANT (vía profile_id + override JSONB versionado)
AUDIT_LOG: transversal a todas las entidades mutables
```

## 1. Tenancy y usuarios

```sql
CREATE TABLE tenant (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    rfc TEXT,
    contact_email TEXT NOT NULL,
    timezone TEXT NOT NULL DEFAULT 'America/Mexico_City',
    status TEXT NOT NULL CHECK (status IN ('active','suspended','trial')),
    retention_config JSONB NOT NULL DEFAULT '{
        "events_years": 5,
        "heartbeats_days": 30,
        "audit_years": 7
    }'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    tenant_id UUID REFERENCES tenant(id),  -- NULL para super_admin
    cognito_sub TEXT UNIQUE NOT NULL,
    email TEXT NOT NULL,
    full_name TEXT,
    role TEXT NOT NULL CHECK (role IN ('super_admin','tenant_admin','rh','supervisor')),
    status TEXT NOT NULL CHECK (status IN ('active','disabled')) DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (tenant_id, email)
);

CREATE TABLE user_plant_access (
    user_id UUID NOT NULL REFERENCES app_user(id),
    plant_id UUID NOT NULL REFERENCES plant(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by UUID REFERENCES app_user(id),
    PRIMARY KEY (user_id, plant_id)
);
```

## 2. Organización

```sql
CREATE TABLE plant (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    name TEXT NOT NULL,
    code TEXT NOT NULL,                          -- código corto del cliente
    address TEXT,
    timezone TEXT,                                -- override del tenant si aplica
    policy_profile_id UUID NOT NULL REFERENCES policy_profile(id),
    policy_override JSONB NOT NULL DEFAULT '{}'::jsonb,
    policy_version INT NOT NULL DEFAULT 1,
    status TEXT NOT NULL CHECK (status IN ('active','inactive')) DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (tenant_id, code)
);

CREATE TABLE shift (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    plant_id UUID NOT NULL REFERENCES plant(id),
    name TEXT NOT NULL,                           -- "Diurno A", "Nocturno"
    type TEXT NOT NULL CHECK (type IN ('diurna','nocturna','mixta')),
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    crosses_midnight BOOLEAN NOT NULL DEFAULT false,
    rest_days SMALLINT[] NOT NULL DEFAULT '{}',   -- {0,6} = domingo y sábado
    legal_max_hours NUMERIC(4,2) NOT NULL,        -- 8/7/7.5 según type
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE employee (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    employee_number TEXT NOT NULL,                -- número de nómina del cliente
    full_name TEXT NOT NULL,
    curp TEXT,
    rfc TEXT,
    nss TEXT,                                      -- Número de Seguridad Social
    default_plant_id UUID REFERENCES plant(id),
    default_shift_id UUID REFERENCES shift(id),
    hire_date DATE,
    termination_date DATE,
    pin_hash TEXT,                                 -- bcrypt del PIN para fallback
    multi_plant_allowed BOOLEAN NOT NULL DEFAULT false,
    status TEXT NOT NULL CHECK (status IN ('active','on_leave','terminated')) DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (tenant_id, employee_number)
);

CREATE TABLE employee_plant (
    employee_id UUID NOT NULL REFERENCES employee(id),
    plant_id UUID NOT NULL REFERENCES plant(id),
    is_default BOOLEAN NOT NULL DEFAULT false,
    valid_from DATE NOT NULL,
    valid_to DATE,
    PRIMARY KEY (employee_id, plant_id, valid_from)
);

CREATE INDEX idx_employee_tenant_status ON employee(tenant_id, status) WHERE deleted_at IS NULL;
CREATE INDEX idx_employee_default_plant ON employee(default_plant_id) WHERE status = 'active';
```

## 3. Métodos de captura

```sql
CREATE TABLE nfc_card (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    uid TEXT NOT NULL,                            -- UID hex de la tarjeta (típicamente MIFARE)
    employee_id UUID REFERENCES employee(id),     -- NULL = sin asignar
    status TEXT NOT NULL CHECK (status IN ('active','blocked','lost')) DEFAULT 'active',
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    blocked_at TIMESTAMPTZ,
    blocked_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, uid)
);

CREATE INDEX idx_nfc_card_active_uid ON nfc_card(tenant_id, uid) WHERE status = 'active';

-- Preparado para v1.1 (vacío en MVP)
CREATE TABLE biometric_template (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    employee_id UUID NOT NULL REFERENCES employee(id),
    type TEXT NOT NULL CHECK (type IN ('fingerprint','face')),
    template_blob BYTEA NOT NULL,                 -- SIEMPRE encriptada con KMS
    template_version INT NOT NULL DEFAULT 1,
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    enrolled_by UUID REFERENCES app_user(id),
    UNIQUE (employee_id, type)
);
```

## 4. Dispositivos

Modelado como **base + subtablas 1:1 por tipo** (table-per-subtype). La tabla `kiosko` contiene todo lo común a un punto de captura; cada tipo concreto tiene su propia subtabla con campos específicos del transporte/protocolo.

**Por qué esta separación:**
- Los campos divergen por tipo: `device_serial` y estado ADMS solo aplican a K50; `pairing_token_hash` y sesión solo a web.
- `device_command` (cola de comandos server→device) hoy solo se consume vía ADMS poll del K50; modelarlo asociado a la subtabla K50 lo hace explícito.
- Agregar un tercer tipo (Android nativo, RFID-only reader, otra marca) es nueva subtabla sin tocar el resto.
- Evita NULLs sistemáticos y CHECKs condicionales por tipo en la tabla base.

### 4.1. Tabla base

```sql
CREATE TABLE kiosko (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    plant_id UUID NOT NULL REFERENCES plant(id),
    name TEXT NOT NULL,                           -- "Entrada principal"
    type TEXT NOT NULL CHECK (type IN ('zkteco_k50','web_kiosko')),
    enabled_methods TEXT[] NOT NULL DEFAULT '{nfc,pin}',
    status TEXT NOT NULL CHECK (status IN ('active','inactive','retired')) DEFAULT 'active',
    -- estado operacional común (todos los tipos lo reportan)
    last_heartbeat_at TIMESTAMPTZ,
    last_sync_at TIMESTAMPTZ,
    app_version TEXT,
    offline_alert_sent BOOLEAN NOT NULL DEFAULT false,  -- usado por DeviceStateService
    -- audit
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX idx_kiosko_plant_status ON kiosko(plant_id, status) WHERE deleted_at IS NULL;
```

### 4.2. Subtabla ZKTeco K50

```sql
CREATE TABLE kiosko_zkteco (
    kiosko_id UUID PRIMARY KEY REFERENCES kiosko(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL,                      -- denormalizado para RLS
    device_serial TEXT NOT NULL,                  -- SN del K50 Pro
    firmware_version TEXT,                        -- reportado vía OPTIONS o config
    encoding TEXT NOT NULL DEFAULT 'UTF-8'        -- override por firmware (ver push-adapter)
        CHECK (encoding IN ('UTF-8','GBK','ISO-8859-1','Windows-1252')),
    biometric_capacity INT,                       -- # huellas/caras que soporta el firmware
    last_adms_poll_at TIMESTAMPTZ,                -- último GET /iclock/getrequest
    UNIQUE (tenant_id, device_serial)
);

CREATE INDEX idx_kiosko_zkteco_serial ON kiosko_zkteco(device_serial);
```

### 4.3. Subtabla Web Kiosko

```sql
CREATE TABLE kiosko_web (
    kiosko_id UUID PRIMARY KEY REFERENCES kiosko(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL,                      -- denormalizado para RLS
    pairing_token_hash TEXT,                      -- bcrypt del device_token activo
    pairing_expires_at TIMESTAMPTZ,               -- TTL del pairing code antes de canjear
    paired_at TIMESTAMPTZ,                        -- cuando se canjeó el code por device_token
    user_agent TEXT,                              -- navegador del dispositivo emparejado
    allowed_origin TEXT,                          -- CORS allowed origin si aplica
    last_session_at TIMESTAMPTZ
);
```

### 4.4. Coherencia kiosko.type ↔ subtabla

Postgres no permite CHECKs cross-table, así que se enforce vía trigger:

```sql
CREATE OR REPLACE FUNCTION validate_kiosko_subtype()
RETURNS TRIGGER AS $$
DECLARE
    v_kiosko_type TEXT;
BEGIN
    SELECT type INTO v_kiosko_type FROM kiosko WHERE id = NEW.kiosko_id;
    IF v_kiosko_type IS NULL THEN
        RAISE EXCEPTION 'kiosko_id % no existe', NEW.kiosko_id;
    END IF;
    IF v_kiosko_type != TG_ARGV[0] THEN
        RAISE EXCEPTION 'kiosko_id % tiene type=% pero se intentó insertar en subtabla de tipo %',
            NEW.kiosko_id, v_kiosko_type, TG_ARGV[0];
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validate_kiosko_zkteco
    BEFORE INSERT OR UPDATE OF kiosko_id ON kiosko_zkteco
    FOR EACH ROW EXECUTE FUNCTION validate_kiosko_subtype('zkteco_k50');

CREATE TRIGGER trg_validate_kiosko_web
    BEFORE INSERT OR UPDATE OF kiosko_id ON kiosko_web
    FOR EACH ROW EXECUTE FUNCTION validate_kiosko_subtype('web_kiosko');
```

**Invariante a nivel app**: el alta de un kiosko inserta `kiosko` + la subtabla correspondiente en la misma transacción Spring (`@Transactional`). Un job de integridad nocturno detecta huérfanos (kiosko sin subtabla) y los marca para revisión.

### 4.5. Heartbeats

```sql
CREATE TABLE kiosko_heartbeat (
    id BIGSERIAL PRIMARY KEY,
    kiosko_id UUID NOT NULL REFERENCES kiosko(id),
    tenant_id UUID NOT NULL,                      -- denormalizado para RLS
    device_time TIMESTAMPTZ NOT NULL,
    server_time TIMESTAMPTZ NOT NULL,
    offset_ms INT NOT NULL,
    events_pending_local INT,
    battery_pct SMALLINT,                         -- nullable: K50 con UPS no reporta
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_heartbeat_kiosko_time ON kiosko_heartbeat(kiosko_id, recorded_at DESC);
```

Heartbeats van contra la tabla base — el formato de heartbeat es uniforme (lo aporta cada cliente: K50 vía ADMS, web vía `POST /api/v1/kiosko/heartbeat`).

### 4.6. Comandos server → device

```sql
-- Cola de comandos pendientes. Hoy solo el K50 los consume (vía ADMS poll).
-- Si en el futuro el web kiosko necesita comandos push, sería otra tabla
-- (transporte distinto: WebSocket/SSE, no polling).
CREATE TABLE device_command (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    kiosko_id UUID NOT NULL REFERENCES kiosko(id),
    command_type TEXT NOT NULL,                   -- 'add_user','del_user','update_pin','reload_config'
    payload JSONB NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('pending','sent','acked','failed')) DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    acked_at TIMESTAMPTZ,
    error_detail TEXT
);

CREATE INDEX idx_device_command_pending ON device_command(kiosko_id, status, created_at)
    WHERE status = 'pending';
```

`device_command.kiosko_id` referencia la base, no la subtabla. Quien encola comandos (`DeviceCommandQueueListener`) ya filtra por `KioskoType.ZKTECO_K50` antes de emitir, ver [12-push-adapter.md §13](./12-push-adapter.md).

### 4.7. Patrón de query

Para resolver un kiosko completo desde su SN (caso típico del adaptador ADMS):

```sql
SELECT k.*, z.device_serial, z.firmware_version, z.encoding, z.last_adms_poll_at
FROM kiosko k
JOIN kiosko_zkteco z ON z.kiosko_id = k.id
WHERE z.device_serial = :sn
  AND k.status = 'active'
  AND k.deleted_at IS NULL;
```

El `KioskoLookupService` cachea esta lectura con TTL 60s (ver [12-push-adapter.md §6](./12-push-adapter.md)).

## 5. Política y calendario

```sql
CREATE TABLE policy_profile (
    id UUID PRIMARY KEY,
    tenant_id UUID REFERENCES tenant(id),         -- NULL = perfil global del producto
    code TEXT NOT NULL,                           -- 'industrial_standard', 'admin_office'
    name TEXT NOT NULL,
    config JSONB NOT NULL,                        -- estructura: ver 05-motor-calculo-horas.md
    is_system BOOLEAN NOT NULL DEFAULT false,
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE plant_policy_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    plant_id UUID NOT NULL REFERENCES plant(id),
    profile_id UUID NOT NULL REFERENCES policy_profile(id),
    override JSONB NOT NULL DEFAULT '{}'::jsonb,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,                     -- NULL = vigente
    created_by UUID REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_plant_policy_active ON plant_policy_history(plant_id, effective_from DESC);

CREATE TABLE holiday (
    id UUID PRIMARY KEY,
    tenant_id UUID REFERENCES tenant(id),         -- NULL = festivo nacional LFT
    plant_id UUID REFERENCES plant(id),           -- NULL = aplica a todo el tenant
    date DATE NOT NULL,
    name TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('lft_official','company','regional')),
    pay_multiplier NUMERIC(3,1) NOT NULL DEFAULT 3.0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX idx_holiday_date_tenant ON holiday(date, tenant_id) WHERE deleted_at IS NULL;
```

## 6. Eventos (núcleo)

```sql
-- INMUTABLE, append-only
CREATE TABLE event_raw (
    id UUID PRIMARY KEY,                          -- UUID v7 cliente, mismo del kiosko
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    plant_id UUID NOT NULL REFERENCES plant(id),
    kiosko_id UUID NOT NULL REFERENCES kiosko(id),
    employee_id UUID NOT NULL REFERENCES employee(id),
    event_type TEXT NOT NULL CHECK (event_type IN ('check_in','check_out','lunch_out','lunch_in')),
    capture_method TEXT NOT NULL CHECK (capture_method IN ('nfc','pin','manual','fingerprint','face')),
    -- timestamps
    device_time TIMESTAMPTZ NOT NULL,             -- lo que dijo el kiosko
    device_offset_ms INT NOT NULL DEFAULT 0,      -- offset detectado
    event_time TIMESTAMPTZ NOT NULL,              -- corregido: device_time - offset
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- referencias
    nfc_card_id UUID REFERENCES nfc_card(id),
    -- metadata
    raw_payload JSONB,                            -- payload original íntegro
    is_valid BOOLEAN NOT NULL DEFAULT true,
    invalidated_by UUID REFERENCES app_user(id),
    invalidated_reason TEXT,
    invalidated_at TIMESTAMPTZ,
    recorded_by_user_id UUID REFERENCES app_user(id)  -- solo para capture_method='manual'
);

CREATE INDEX idx_event_employee_time ON event_raw(tenant_id, employee_id, event_time);
CREATE INDEX idx_event_plant_time ON event_raw(tenant_id, plant_id, event_time);
CREATE INDEX idx_event_received ON event_raw(received_at);

-- correcciones (referencia)
CREATE TABLE event_correction (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    event_raw_id UUID REFERENCES event_raw(id),   -- NULL si action='create'
    action TEXT NOT NULL CHECK (action IN ('create','invalidate','adjust_time','adjust_type')),
    new_event_time TIMESTAMPTZ,
    new_event_type TEXT,
    reason TEXT NOT NULL,
    authorized_by UUID NOT NULL REFERENCES app_user(id),
    authorized_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resulting_event_id UUID REFERENCES event_raw(id)  -- nuevo evento creado por la corrección
);

CREATE INDEX idx_correction_event ON event_correction(event_raw_id);

CREATE TABLE review_queue (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    plant_id UUID NOT NULL REFERENCES plant(id),
    employee_id UUID REFERENCES employee(id),
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason TEXT NOT NULL CHECK (reason IN (
        'multi_kiosko_conflict','missing_check_in','missing_check_out',
        'extended_shift','out_of_window','other'
    )),
    related_event_ids UUID[] NOT NULL DEFAULT '{}',
    status TEXT NOT NULL CHECK (status IN ('pending','resolved','dismissed')) DEFAULT 'pending',
    resolved_by UUID REFERENCES app_user(id),
    resolved_at TIMESTAMPTZ,
    resolution TEXT
);

CREATE INDEX idx_review_pending ON review_queue(tenant_id, plant_id, status, detected_at)
    WHERE status = 'pending';
```

## 7. Cálculo y nómina

```sql
-- Resumen calculado por empleado-día (idempotente, recalculable)
CREATE TABLE attendance_day (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    plant_id UUID NOT NULL REFERENCES plant(id),
    employee_id UUID NOT NULL REFERENCES employee(id),
    business_date DATE NOT NULL,                  -- día contable según política
    shift_id UUID REFERENCES shift(id),
    -- horas clasificadas
    hours_regular NUMERIC(5,2) NOT NULL DEFAULT 0,
    hours_regular_night NUMERIC(5,2) NOT NULL DEFAULT 0,
    hours_overtime_2x NUMERIC(5,2) NOT NULL DEFAULT 0,
    hours_overtime_3x NUMERIC(5,2) NOT NULL DEFAULT 0,
    hours_holiday NUMERIC(5,2) NOT NULL DEFAULT 0,
    hours_sunday_premium NUMERIC(5,2) NOT NULL DEFAULT 0,
    hours_rest_day_worked NUMERIC(5,2) NOT NULL DEFAULT 0,
    -- estado
    is_late BOOLEAN NOT NULL DEFAULT false,
    late_minutes INT NOT NULL DEFAULT 0,
    is_complete BOOLEAN NOT NULL DEFAULT false,
    is_absent BOOLEAN NOT NULL DEFAULT false,
    -- versión de política usada
    policy_version_used INT NOT NULL,
    policy_snapshot JSONB NOT NULL,               -- snapshot del JSON al momento del cálculo
    -- metadata
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    recalc_count INT NOT NULL DEFAULT 0,
    UNIQUE (employee_id, business_date)
);

CREATE INDEX idx_attendance_plant_date ON attendance_day(plant_id, business_date);
CREATE INDEX idx_attendance_tenant_date ON attendance_day(tenant_id, business_date);

CREATE TABLE pay_period (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    plant_id UUID REFERENCES plant(id),           -- NULL = nivel tenant
    type TEXT NOT NULL CHECK (type IN ('weekly','biweekly','fortnightly','monthly')),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('open','closed','locked')) DEFAULT 'open',
    closed_at TIMESTAMPTZ,
    closed_by UUID REFERENCES app_user(id),
    locked_after_days INT,                        -- de la política
    UNIQUE (tenant_id, plant_id, start_date)
);

CREATE TABLE pay_period_summary (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    pay_period_id UUID NOT NULL REFERENCES pay_period(id),
    employee_id UUID NOT NULL REFERENCES employee(id),
    total_regular NUMERIC(7,2) NOT NULL DEFAULT 0,
    total_regular_night NUMERIC(7,2) NOT NULL DEFAULT 0,
    total_ot_2x NUMERIC(7,2) NOT NULL DEFAULT 0,
    total_ot_3x NUMERIC(7,2) NOT NULL DEFAULT 0,
    total_holiday NUMERIC(7,2) NOT NULL DEFAULT 0,
    total_sunday_premium NUMERIC(7,2) NOT NULL DEFAULT 0,
    total_rest_day_worked NUMERIC(7,2) NOT NULL DEFAULT 0,
    total_lates INT NOT NULL DEFAULT 0,
    total_absences INT NOT NULL DEFAULT 0,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (pay_period_id, employee_id)
);
```

## 8. Audit log (transversal)

```sql
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    actor_user_id UUID REFERENCES app_user(id),
    actor_kiosko_id UUID REFERENCES kiosko(id),
    action TEXT NOT NULL,                         -- 'employee.update', 'event.invalidate', 'period.close'
    entity_type TEXT NOT NULL,
    entity_id UUID,
    before JSONB,
    after JSONB,
    reason TEXT,
    ip_address INET,
    user_agent TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_entity ON audit_log(tenant_id, entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_actor ON audit_log(tenant_id, actor_user_id, occurred_at DESC);
CREATE INDEX idx_audit_time ON audit_log(occurred_at);
```

## Idempotencia

```sql
CREATE TABLE idempotency_record (
    key TEXT PRIMARY KEY,                         -- valor del header Idempotency-Key
    tenant_id UUID NOT NULL,
    request_hash TEXT NOT NULL,                   -- hash del body para detectar mismatches
    response_status INT NOT NULL,
    response_body JSONB,
    expires_at TIMESTAMPTZ NOT NULL              -- ttl 24h
);

CREATE INDEX idx_idempotency_expires ON idempotency_record(expires_at);
```

## Row-Level Security (RLS)

Habilitado en TODAS las tablas con `tenant_id`:

```sql
ALTER TABLE event_raw ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON event_raw
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Para inserts con tenant_id explícito
CREATE POLICY tenant_isolation_insert ON event_raw FOR INSERT
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

El backend hace `SET app.tenant_id = '...'` al inicio de cada request (vía Spring filter + ThreadLocal). Si el developer olvida filtrar, Postgres rechaza.

**Excepciones legítimas** (super_admin global, jobs nocturnos):
```sql
SET app.tenant_id = NULL;  -- permite ver todos los tenants
ALTER TABLE event_raw FORCE ROW LEVEL SECURITY;  -- política aplica también a propietarios
```

## Audit log via triggers

```sql
CREATE OR REPLACE FUNCTION write_audit_log()
RETURNS TRIGGER AS $$
DECLARE
    v_actor_user_id UUID := current_setting('app.actor_user_id', true)::uuid;
    v_actor_kiosko_id UUID := current_setting('app.actor_kiosko_id', true)::uuid;
    v_tenant_id UUID := current_setting('app.tenant_id', true)::uuid;
BEGIN
    INSERT INTO audit_log (
        tenant_id, actor_user_id, actor_kiosko_id, action,
        entity_type, entity_id,
        before, after,
        occurred_at
    ) VALUES (
        v_tenant_id,
        v_actor_user_id,
        v_actor_kiosko_id,
        TG_OP,
        TG_TABLE_NAME,
        COALESCE(NEW.id, OLD.id),
        CASE WHEN TG_OP IN ('UPDATE','DELETE') THEN to_jsonb(OLD) ELSE NULL END,
        CASE WHEN TG_OP IN ('INSERT','UPDATE') THEN to_jsonb(NEW) ELSE NULL END,
        now()
    );
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

-- Aplicado a tablas mutables:
CREATE TRIGGER trg_audit_employee
    AFTER INSERT OR UPDATE OR DELETE ON employee
    FOR EACH ROW EXECUTE FUNCTION write_audit_log();

-- Aplicado a: plant, kiosko, kiosko_zkteco, kiosko_web, nfc_card, shift,
--             employee, employee_plant, policy_profile, plant_policy_history,
--             holiday, pay_period (close/lock).
-- NO aplicado a event_raw (es append-only, los cambios pasan por event_correction).
-- NO aplicado a kiosko_heartbeat (alta frecuencia, ya tiene su propia auditoría implícita).
```

## Estimaciones de tamaño (5 años, 2 tenants × 500 empleados)

| Tabla | Filas/año aprox. | Tamaño/año |
|---|---|---|
| `event_raw` | 500 emp × 4 eventos × 250 días × 2 tenants = 1M | ~250 MB |
| `attendance_day` | 500 × 250 × 2 = 250K | ~100 MB |
| `kiosko_heartbeat` | 5 kioskos × 1440 hb/día × 365 × 2 = 5.2M | ~500 MB |
| `audit_log` | ~500K (estimado) | ~200 MB |
| `pay_period_summary` | 500 × 52 × 2 = 52K | ~10 MB |

**Total 5 años**: ~5 GB. Sin necesidad de partitioning en MVP. Reevaluar si llegamos a 50+ tenants o 5,000+ empleados/tenant.

## Estrategia de retención

Configurada por tenant en `tenant.retention_config` JSONB:

```json
{
  "events_years": 5,           // LFT Art. 804 obliga 5 años de registros laborales
  "heartbeats_days": 30,
  "audit_years": 7,            // mayor retención para defensa legal
  "calculated_days_years": 5
}
```

Job nocturno lee config y aplica DELETE/archivado. Para eventos viejos, **archivado a S3 Glacier** (no DELETE) para mantener disponibilidad legal con costo bajo.
